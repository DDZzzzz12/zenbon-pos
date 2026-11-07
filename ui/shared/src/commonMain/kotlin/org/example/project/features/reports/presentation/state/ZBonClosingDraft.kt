package org.example.project.features.reports.presentation.state

import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.core.utils.ClosingTopUpRule
import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.core.utils.toUserInputDecimalOrNull
import org.example.project.money.Decimal
import org.example.project.ui.screens.reports.components.zBonCountedPrefill

/**
 * The "Z-Bon erstellen" dialog while it is open: the drawer count, the tip payouts booked in the
 * closing (F58) and — F59 — the top-up from another cash book when the cash payouts exceed the drawer.
 *
 * [expectedCash] is the server's `expectedCash` (book + unbooked cash takings) BEFORE the payouts;
 * [expectedAfterPayouts] is what the drawer holds once the top-up is put in and the checked CASH
 * payouts are handed out — what the count field shows as its placeholder and what the server compares the
 * count against. A bank/payroll row moves no cash and leaves both figures alone.
 *
 * **Without a signer** ([movementsAllowed] false) the count and the top-up are hidden and never sent,
 * and every payout row is forced to bank/payroll ([effectiveTipRows]) — the only kind that books no
 * Kassenbuch entry and so needs no signer.
 *
 * **Kassenminus (F59).** The drawer cannot pay out more than it holds. When the cash payouts exceed
 * [expectedCash] by [shortfall], the closing is blocked until a top-up of at least [shortfall] from
 * one of [topUpSources] holding that much is chosen — the server books it as a Geldtransit into the
 * Ladenkasse inside the closing, before the payouts, and refuses the closing otherwise.
 *
 * @property countText the count field as typed. It starts EMPTY ("empty = not counted"); the drawer
 *   expectation is shown beside it as [countPlaceholder], never as a value, so an untouched field can
 *   not book a phantom difference if the book moved since the dialog opened (FO-33)
 * @property countEdited the operator typed into the count field; [countedAmount] is only sent then
 * @property tipRows empty when the operator may not pay out tips — the section is then hidden
 * @property topUpSources the shop's other active, non-fiscal cash books with their balances
 * @property topUpSourceId the chosen source, or null
 * @property topUpText the top-up amount as typed; follows [floatPreservingTopUp] until [topUpEdited]
 * @property unbookedCashTakings the part of [expectedCash] that is this period's cash takings (the
 *   rest is the float, the book balance); null while unknown
 */
data class ZBonClosingDraft(
    val expectedCash: String? = null,
    val tipRows: List<ClosingTipPayoutRow> = emptyList(),
    val countText: String = "",
    val countEdited: Boolean = false,
    val topUpSources: List<ClosingTopUpSource> = emptyList(),
    val topUpSourceId: Long? = null,
    val topUpText: String = "",
    val topUpEdited: Boolean = false,
    val unbookedCashTakings: String? = null,
    /** Who would sign this closing's movements (`GET /api/z-bon/closing-readiness`); null while unknown or unavailable. */
    val readiness: ZBonDto.ClosingReadinessDto? = null,
) {
    /**
     * The operator changed something from how the dialog opened it: typed a count or a top-up, or
     * edited a tip row. A freshly opened (or only auto-prefilled) dialog is clean.
     */
    val isDirty: Boolean
        get() = countEdited || topUpEdited || tipRows.any { it.isEdited }

    /**
     * The rows as they are paid. Without a signer ([movementsAllowed] false) every row is forced to
     * [TipPayoutMethod.BANK_PAYROLL]: it books no Kassenbuch entry and moves no cash, so it needs no
     * signer, while a cash payout would. Every figure and the request derive from these, never from
     * [tipRows] directly.
     */
    val effectiveTipRows: List<ClosingTipPayoutRow>
        get() = if (movementsAllowed) tipRows else tipRows.map { it.copy(method = TipPayoutMethod.BANK_PAYROLL) }

    /** Σ of the checked, valid CASH payouts — what leaves the drawer. */
    val tipPayoutTotal: Decimal
        get() = effectiveTipRows.mapNotNull { it.cashPayout }.fold(Decimal.ZERO) { acc, d -> acc + d }

    /**
     * How much the cash payouts exceed the drawer, or null when they do not (or the expectation is
     * unknown — the server still guards then).
     */
    val shortfall: Decimal?
        get() {
            val expected = expectedCash?.let { Decimal.parseOrNull(it) } ?: return null
            val takings = takingsOrZero
            return ClosingTopUpRule.shortfall(tipPayoutTotal, float = expected - takings, takings = takings)
        }

    private val takingsOrZero: Decimal get() = unbookedCashTakings?.let { Decimal.parseOrNull(it) } ?: Decimal.ZERO

    /**
     * The top-up the dialog prefills: the amount that leaves the float untouched
     * ([ClosingTopUpRule.floatPreservingTopUp]), or null when no top-up is needed. Without a known
     * takings figure it is the whole cash payout — still float-preserving, if conservative.
     */
    val floatPreservingTopUp: Decimal?
        get() = shortfall?.let { ClosingTopUpRule.floatPreservingTopUp(tipPayoutTotal, takingsOrZero) }

    /** The typed top-up, or null when none is needed or it does not parse. */
    val topUpAmount: Decimal?
        get() = if (shortfall == null) null else topUpText.toUserInputDecimalOrNull()?.takeIf { it > Decimal.ZERO }

    /** The books that could cover the top-up the closing needs (at least the [shortfall]). */
    val eligibleTopUpSources: List<ClosingTopUpSource>
        get() {
            val missing = shortfall ?: return emptyList()
            return topUpSources.filter { it.covers(missing) }
        }

    /** The chosen source, when it is eligible and holds the amount typed. */
    val topUpSource: ClosingTopUpSource?
        get() {
            val needed = topUpAmount ?: shortfall ?: return null
            return eligibleTopUpSources.firstOrNull { it.cashBookId == topUpSourceId }?.takeIf { it.covers(needed) }
        }

    /** The typed top-up is missing, malformed or below the [shortfall]. */
    val topUpTooLow: Boolean
        get() {
            val missing = shortfall ?: return false
            val amount = topUpAmount ?: return true
            return amount < missing
        }

    /** The cash payouts need a top-up and no valid one is chosen — blocks the closing. */
    val topUpBlocksClosing: Boolean get() = shortfall != null && (topUpTooLow || topUpSource == null)

    /** The drawer after the top-up and the cash payouts, or null while the expectation is unknown. */
    val expectedAfterPayouts: Decimal?
        get() =
            expectedCash?.let { Decimal.parseOrNull(it) }?.let { expected ->
                expected + (if (shortfall != null) topUpAmount ?: Decimal.ZERO else Decimal.ZERO) - tipPayoutTotal
            }

    /** The count as a canonical decimal, or null when the operator did not count. */
    val countedAmount: String? get() = countText.trim().takeIf { it.isNotEmpty() }?.toCanonicalMoneyOrNull()

    /** The expected drawer in the field's own comma convention, shown as the placeholder — never sent. */
    val countPlaceholder: String get() = zBonCountedPrefill(expectedAfterPayouts?.toPlainString())

    /** A non-empty count that does not parse. */
    val countInvalid: Boolean get() = countText.trim().isNotEmpty() && countedAmount == null

    /** Count, cash payouts and top-up may be booked. Unknown readiness does not restrict: the server still guards. */
    val movementsAllowed: Boolean get() = readiness?.movementsAllowed ?: true

    /** Not even a plain closing is possible from here. */
    val blockedBySigner: Boolean get() = readiness?.blocksClosing ?: false

    /**
     * Without a signer the count and top-up are hidden, so neither can block; the payout amounts are
     * still checked because bank/payroll payouts are still sent.
     */
    val canConfirm: Boolean
        get() =
            !blockedBySigner &&
                effectiveTipRows.none { it.blocksClosing } &&
                (!movementsAllowed || (!countInvalid && !topUpBlocksClosing))

    /**
     * The closing request. The count is sent only when the operator typed one ([countEdited]).
     * Without a signer it carries no count and no top-up, and only the
     * bank/payroll payouts ([effectiveTipRows]) — those book nothing the signer would have to sign.
     */
    fun toRequest(): ZBonDto.CreateZBonRequestDto =
        ZBonDto.CreateZBonRequestDto(
            countedAmount = if (movementsAllowed && countEdited) countedAmount else null,
            tipPayouts = payoutRequests(),
            topUp = if (movementsAllowed) topUpRequest() else null,
        )

    /** The `tipPayouts` of the closing request: one per checked row, canonical dot decimal, with its method. */
    fun payoutRequests(): List<ZBonDto.CreateTipPayoutRequestDto> =
        effectiveTipRows.mapNotNull { row ->
            row.payout?.let {
                ZBonDto.CreateTipPayoutRequestDto(employeeId = row.employeeId, amount = it.toPlainString(), method = row.method)
            }
        }

    /** The closing request's `topUp`, or null when the drawer covers the cash payouts. */
    fun topUpRequest(): ZBonDto.ClosingTopUpRequestDto? {
        if (shortfall == null) return null
        val source = topUpSource ?: return null
        val amount = topUpAmount ?: return null
        return ZBonDto.ClosingTopUpRequestDto(fromCashBookId = source.cashBookId, amount = amount.toPlainString())
    }

    /**
     * Re-derives the top-up defaults after any change: the amount follows [floatPreservingTopUp] until
     * the operator typed one, and the source falls back to the first eligible book when none (or one no
     * longer eligible) is chosen. [formatPrefill] renders the prefill as the operator types it.
     */
    fun withTopUpDefaults(formatPrefill: (Decimal) -> String): ZBonClosingDraft {
        val prefill = floatPreservingTopUp ?: return copy(topUpText = if (topUpEdited) topUpText else "")
        val text = if (topUpEdited) topUpText else formatPrefill(prefill)
        val withText = copy(topUpText = text)
        // Keep the operator's eligible choice; otherwise prefer a book that holds the whole prefill.
        val amount = withText.topUpAmount ?: prefill
        val sourceId =
            withText.topUpSourceId?.takeIf { id -> withText.eligibleTopUpSources.any { it.cashBookId == id } }
                ?: withText.eligibleTopUpSources.firstOrNull { it.covers(amount) }?.cashBookId
                ?: withText.eligibleTopUpSources.firstOrNull()?.cashBookId
        return withText.copy(topUpSourceId = sourceId)
    }
}
