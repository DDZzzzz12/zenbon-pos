package org.example.project.features.reports.presentation.state

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.CashBookApiClient
import org.example.project.core.data.remote.KassenbuchApiClient
import org.example.project.core.data.remote.ZBonApiClient
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.money.Decimal
import org.example.project.money.format

/**
 * The "Z-Bon erstellen" dialog's logic, shared by the till (`ZBonViewModel`) and the adminApp
 * (`ZBonClosingViewModel`) so the payout, shortfall and signer rules exist once. Compose-free.
 *
 * The draft lives in the host's state; the controller reads and writes it through [readDraft] and
 * [writeDraft], synchronously, so a host's state stays the single source of truth.
 *
 * @property onExpectedCash told the drawer expectation as soon as it is known, for hosts that also
 *   keep it outside the draft
 */
class ZBonClosingController(
    private val scope: CoroutineScope,
    private val zBonApiClient: ZBonApiClient,
    private val kassenbuchApiClient: KassenbuchApiClient,
    private val cashBookApiClient: CashBookApiClient,
    private val readDraft: () -> ZBonClosingDraft?,
    private val writeDraft: (ZBonClosingDraft?) -> Unit,
    private val onExpectedCash: (String) -> Unit = {},
) {
    private val logger = Logger.withTag("ZBonClosingController")

    /**
     * Opens the "Z-Bon erstellen" dialog (F58): loads the signer readiness, the drawer expectation and
     * — when the operator may pay out tips ([includeTipPayouts], the till half of `CASHBOOK_WRITE`) —
     * the open period's employee tips, one row per employee, checked and prefilled with their whole
     * tip. Every lookup fails soft: the count and the payouts are optional, so none may stop the
     * operator from closing the day; tips not paid here can still be paid on the created Z-Bon. The
     * server repeats every check, so an unknown readiness restricts nothing.
     */
    fun open(includeTipPayouts: Boolean) {
        writeDraft(ZBonClosingDraft())
        scope.launch {
            runCatchingApi { zBonApiClient.getClosingReadiness() }
                .onSuccess { readiness -> update { it.copy(readiness = readiness) } }
                .onError { logger.w { "open: signer readiness unavailable, the server still guards" } }
        }
        scope.launch {
            runCatchingApi { kassenbuchApiClient.getCashCountExpectation() }
                .onSuccess { expectation ->
                    onExpectedCash(expectation.expectedCash)
                    updateDerived {
                        it.copy(expectedCash = expectation.expectedCash, unbookedCashTakings = expectation.unbookedCashTakings)
                    }
                }.onError { logger.w { "open: drawer expectation unavailable, continuing without it" } }
        }
        if (!includeTipPayouts) return
        scope.launch {
            runCatchingApi { zBonApiClient.getOpenPeriodTips() }
                .onSuccess { tips ->
                    val rows = tips.mapNotNull { it.toClosingRow() }
                    updateDerived { it.copy(tipRows = rows) }
                }.onError { logger.w { "open: open-period tips unavailable, closing without in-closing payouts" } }
        }
        // F59: the books a shortfall can be topped up from — the shop's other active, non-fiscal books.
        // Fails soft like the rest: without them a shortfall simply cannot be confirmed here.
        scope.launch {
            runCatchingApi { cashBookApiClient.listBooks() }
                .onSuccess { books ->
                    val sources =
                        books
                            .filter { it.isActive && !it.isFiscal }
                            .map { ClosingTopUpSource(cashBookId = it.id, name = it.name, balance = it.currentBalance) }
                    updateDerived { it.copy(topUpSources = sources) }
                }.onError { logger.w { "open: cash books unavailable, no top-up offered" } }
        }
    }

    /** F59: pays one employee's in-closing tip in cash or settles it via bank/payroll. */
    fun setTipMethod(
        employeeId: String,
        method: TipPayoutMethod,
    ) = updateRow(employeeId) { it.copy(method = method) }

    /** F59: the operator picked the cash book the shortfall is topped up from. */
    fun selectTopUpSource(cashBookId: Long) = updateDerived { it.copy(topUpSourceId = cashBookId) }

    /** F59: the operator typed the top-up amount; from now on it no longer follows the shortfall. */
    fun updateTopUpAmount(text: String) = updateDerived { it.copy(topUpText = text, topUpEdited = true) }

    /** Checks or unchecks one employee's in-closing payout. */
    fun setTipChecked(
        employeeId: String,
        checked: Boolean,
    ) = updateRow(employeeId) { it.copy(checked = checked) }

    /** The operator edited one employee's in-closing payout amount (capped at their tip by [ClosingTipPayoutRow.check]). */
    fun updateTipAmount(
        employeeId: String,
        text: String,
    ) = updateRow(employeeId) { it.copy(amount = text) }

    /** The operator typed into the count field: only then is a count sent (FO-33). */
    fun updateCount(text: String) = update { it.copy(countText = text, countEdited = true) }

    /** Closes the dialog without closing the day. */
    fun dismiss() = writeDraft(null)

    /**
     * The request to send, closing the dialog — or null, with the dialog left open, while the count
     * does not parse, a checked row fails its amount check or the signer blocks the closing. The
     * server repeats every check before anything is booked.
     */
    fun takeRequest(): ZBonDto.CreateZBonRequestDto? {
        val draft = readDraft() ?: return null
        if (!draft.canConfirm) return null
        writeDraft(null)
        return draft.toRequest()
    }

    private fun update(change: (ZBonClosingDraft) -> ZBonClosingDraft) {
        val draft = readDraft() ?: return
        writeDraft(change(draft))
    }

    private fun updateDerived(change: (ZBonClosingDraft) -> ZBonClosingDraft) = update { change(it).withDerivedFields() }

    private fun updateRow(
        employeeId: String,
        change: (ClosingTipPayoutRow) -> ClosingTipPayoutRow,
    ) = updateDerived { draft -> draft.copy(tipRows = draft.tipRows.map { if (it.employeeId == employeeId) change(it) else it }) }

    /** The top-up defaults; the count field is never prefilled (its expectation is only the placeholder). */
    private fun ZBonClosingDraft.withDerivedFields(): ZBonClosingDraft = withTopUpDefaults { it.format(TOP_UP_PREFILL) }

    /** A dialog row prefilled with the whole tip (rounded down to cents), or null for a tip of nothing. */
    private fun ZBonDto.OpenPeriodTipDto.toClosingRow(): ClosingTipPayoutRow? {
        val total = Decimal.parseOrNull(tipTotal)?.takeIf { it > Decimal.ZERO } ?: return null
        return ClosingTipPayoutRow(
            employeeId = employeeId,
            employeeName = employeeName,
            total = tipTotal,
            cash = cashTip,
            card = cardTip,
            amount = total.format(TIP_PAYOUT_PREFILL),
        )
    }
}
