/**
 * ViewModel for Z-Bon (Daily Closing Report) management.
 *
 * Manages Z-Bon creation, history viewing, and date range filtering
 * with proper state management and error handling.
 *
 * @property zBonApiClient Client for Z-Bon API operations
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.reports.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.CashBookApiClient
import org.example.project.core.data.remote.KassenbuchApiClient
import org.example.project.core.data.remote.ZBonApiClient
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.state.DateRange
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.presentation.strings.keys.TipPayoutKeys
import org.example.project.core.utils.LanguageManager
import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.features.reports.presentation.state.ClosingTipPayoutRow
import org.example.project.features.reports.presentation.state.TIP_PAYOUT_PREFILL
import org.example.project.features.reports.presentation.state.TipPayoutAmountCheck
import org.example.project.features.reports.presentation.state.TipPayoutDraft
import org.example.project.features.reports.presentation.state.TipReversalDraft
import org.example.project.features.reports.presentation.state.ZBonClosingController
import org.example.project.features.reports.presentation.state.ZBonState
import org.example.project.money.Decimal
import org.example.project.money.format
import org.example.project.ui.components.formatMoneyForDisplay

/**
 * ViewModel for managing Z-Bon functionality.
 *
 * Handles Z-Bon creation, history retrieval, and state management
 * independently from main reports screen.
 */
class ZBonViewModel(
    private val zBonApiClient: ZBonApiClient,
    private val kassenbuchApiClient: KassenbuchApiClient,
    private val languageManager: LanguageManager,
    private val cashBookApiClient: CashBookApiClient,
) : ViewModel() {
    private val logger = Logger.withTag("ZBonViewModel")

    /** PosSuccessWatcher shows success text verbatim, so it is resolved here in the operator's language. */
    private fun localized(key: StringKey): String = getString(key, languageManager.currentLanguage.value)

    private val _zBonState = MutableStateFlow(ZBonState())
    val zBonState: StateFlow<ZBonState> = _zBonState.asStateFlow()

    /**
     * Create a new Z-Bon (daily closing report).
     *
     * Validates that all table sessions are closed before creating.
     * On success, sets the created Z-Bon as selected for display.
     *
     * @param onSuccess Callback on successful creation
     */
    fun createZBon(onSuccess: () -> Unit = {}) = createZBonWithCount(countedAmount = null, onSuccess = onSuccess)

    /**
     * Closes the day in ONE request. The drawer count travels with it and the server books the
     * Kassendifferenz only once the closing's preconditions pass — booking it in a separate call
     * first left a difference outside any closing whenever the closing was then refused
     * (E2E-2026-09-18 #8).
     *
     * Creating never prints — printing stays the detail view's own button. On success the new
     * closing opens where the operator can see it: as the history's selected Z-Bon (and the history
     * reloads so the list carries it) when [opensHistory], otherwise as [ZBonState.createdZBon] for
     * the summary dialog, since the history routes answer 403 without `ZBON_MANAGE`.
     */
    fun createZBonWithCount(
        countedAmount: String?,
        opensHistory: Boolean = true,
        tipPayouts: List<ZBonDto.CreateTipPayoutRequestDto> = emptyList(),
        topUp: ZBonDto.ClosingTopUpRequestDto? = null,
        onSuccess: () -> Unit = {},
    ) {
        logger.i {
            "ZBonViewModel.createZBon: creating new Z-Bon (count=${countedAmount != null}, tipPayouts=${tipPayouts.size}, topUp=${topUp != null})"
        }
        viewModelScope.launch {
            runCatchingApi { zBonApiClient.createZBon(countedAmount, tipPayouts, topUp) }
                .onSuccess { resp ->
                    logger.i { "ZBonViewModel.createZBon: created successfully, id=${resp.id}" }
                    _zBonState.update { state ->
                        if (opensHistory) state.copy(selectedZBon = resp) else state.copy(createdZBon = resp)
                    }
                    if (opensHistory) loadZBonHistory()
                    onSuccess()
                }.onError { uiError ->
                    logger.e { "ZBonViewModel.createZBon: failed with error=$uiError" }
                    _zBonState.update { it.copy(error = uiError) }
                }
        }
    }

    /**
     * Loads what the drawer should hold right now so the confirm dialog can prefill the count.
     *
     * The expected figure is the server's `expectedCash` — book balance PLUS the cash of bills not
     * yet closed — never the bare book balance: cash sales reach the book only when the Z-Bon
     * books its aggregate, so a count prefilled with the book alone is short by the day's takings
     * and books them as a phantom surplus.
     *
     * A failure is swallowed to null on purpose: the count is optional, so a lookup failure must
     * never stop the operator from closing the day.
     */
    fun loadExpectedCashBalance() {
        viewModelScope.launch {
            runCatchingApi { kassenbuchApiClient.getCashCountExpectation() }
                .onSuccess { expectation -> _zBonState.update { it.copy(expectedCashBalance = expectation.expectedCash) } }
                .onError { logger.w { "loadExpectedCashBalance: prefill unavailable, continuing without it" } }
        }
    }

    /** The closing dialog's logic, shared with the adminApp; the draft stays in [zBonState]. */
    private val closing =
        ZBonClosingController(
            scope = viewModelScope,
            zBonApiClient = zBonApiClient,
            kassenbuchApiClient = kassenbuchApiClient,
            cashBookApiClient = cashBookApiClient,
            readDraft = { _zBonState.value.closingDraft },
            writeDraft = { draft -> _zBonState.update { it.copy(closingDraft = draft) } },
            onExpectedCash = { cash -> _zBonState.update { it.copy(expectedCashBalance = cash) } },
        )

    /** Opens the "Z-Bon erstellen" dialog (F58); see [ZBonClosingController.open]. */
    fun openClosingDialog(includeTipPayouts: Boolean) = closing.open(includeTipPayouts)

    /** F59: pays one employee's in-closing tip in cash or settles it via bank/payroll. */
    fun setClosingTipMethod(
        employeeId: String,
        method: TipPayoutMethod,
    ) = closing.setTipMethod(employeeId, method)

    /** F59: the operator picked the cash book the shortfall is topped up from. */
    fun selectClosingTopUpSource(cashBookId: Long) = closing.selectTopUpSource(cashBookId)

    /** F59: the operator typed the top-up amount; from now on it no longer follows the shortfall. */
    fun updateClosingTopUpAmount(text: String) = closing.updateTopUpAmount(text)

    /** Checks or unchecks one employee's in-closing payout. */
    fun setClosingTipChecked(
        employeeId: String,
        checked: Boolean,
    ) = closing.setTipChecked(employeeId, checked)

    /** The operator edited one employee's in-closing payout amount (capped at their tip by [ClosingTipPayoutRow.check]). */
    fun updateClosingTipAmount(
        employeeId: String,
        text: String,
    ) = closing.updateTipAmount(employeeId, text)

    /** The operator typed into the count field: from now on it no longer follows the expectation. */
    fun updateClosingCount(text: String) = closing.updateCount(text)

    /** Closes the dialog without closing the day. */
    fun dismissClosingDialog() = closing.dismiss()

    /**
     * Closes the day from the dialog in ONE request: the count and every checked, valid payout. Refused
     * locally — no request — while the count does not parse or a checked row fails its amount check;
     * the server repeats every check before anything is booked.
     */
    fun confirmClosing(
        opensHistory: Boolean = true,
        onSuccess: () -> Unit = {},
    ) {
        val request = closing.takeRequest() ?: return
        createZBonWithCount(
            countedAmount = request.countedAmount,
            opensHistory = opensHistory,
            tipPayouts = request.tipPayouts,
            topUp = request.topUp,
            onSuccess = onSuccess,
        )
    }

    /**
     * Records the drawer count, then creates the Z-Bon — in that order, so the closing's sweep
     * picks the difference entry up into the closing that CAUSED it.
     *
     * A null [countedAmount] means the operator did not count: no call is made and the Z-Bon
     * closes exactly as before. A failed count aborts before the Z-Bon, so a difference can never
     * be stranded outside its closing, and the dialog's input is not thrown away.
     *
     * @param countedAmount Counted cash as a canonical decimal string, or null when not counted
     */

    /**
     * Clear the error state.
     */
    fun clearError() {
        _zBonState.update { it.copy(error = null) }
    }

    /**
     * Clear the success message.
     */
    fun clearSuccessMessage() {
        _zBonState.update { it.copy(successMessage = null) }
    }

    /** Close the summary dialog of a Z-Bon just created by an operator without history access. */
    fun dismissCreatedZBon() {
        _zBonState.update { it.copy(createdZBon = null) }
    }

    /**
     * Clear the selected Z-Bon and close the dialog.
     */
    fun clearSelectedZBon() {
        _zBonState.update { state ->
            state.copy(
                selectedZBon = null,
            )
        }
    }

    /**
     * Select a new date range for Z-Bon history and reload data.
     *
     * @param dateRange The new date range to apply
     */
    fun selectDateRange(dateRange: DateRange) {
        _zBonState.update { currentState ->
            currentState.copy(selectedDateRange = dateRange)
        }
        loadZBonHistory()
    }

    /**
     * Set custom date range for Z-Bon history.
     *
     * Updates the state with a custom date range and fetches
     * Z-Bon history for the specified period.
     *
     * @param startDate Start date in ISO format (YYYY-MM-DD)
     * @param endDate End date in ISO format (YYYY-MM-DD)
     */
    fun setCustomDateRange(
        startDate: String,
        endDate: String,
    ) {
        _zBonState.update { currentState ->
            currentState.copy(
                selectedDateRange = DateRange.CUSTOM,
                customDateRange = Pair(startDate, endDate),
            )
        }
        loadZBonHistory()
    }

    /**
     * Load Z-Bon history for the currently selected date range.
     *
     * Fetches Z-Bon summaries from the API based on the selected
     * date range filter (This Week, This Month, Last 3 Months).
     */
    fun loadZBonHistory() {
        logger.i { "ZBonViewModel.loadZBonHistory: loading Z-Bon history" }
        viewModelScope.launch {
            val currentState = _zBonState.value

            _zBonState.update { it.copy(isLoading = true, error = null) }

            val (startDate, endDate) =
                if (
                    currentState.selectedDateRange == DateRange.CUSTOM &&
                    currentState.customDateRange != null
                ) {
                    currentState.customDateRange!!
                } else {
                    currentState.selectedDateRange.toDatePair()
                }

            logger.d { "ZBonViewModel.loadZBonHistory: date range $startDate to $endDate" }

            runCatchingApi { zBonApiClient.getZBonHistory(startDate, endDate) }
                .onSuccess { history ->
                    logger.i { "ZBonViewModel.loadZBonHistory: loaded ${history.size} Z-Bons" }
                    _zBonState.update {
                        it.copy(
                            zbonHistory = history,
                            isLoading = false,
                            error = null,
                        )
                    }
                }.onError { uiError ->
                    logger.e { "ZBonViewModel.loadZBonHistory: failed with error=$uiError" }
                    _zBonState.update {
                        it.copy(
                            zbonHistory = emptyList(),
                            isLoading = false,
                            error = uiError,
                        )
                    }
                }
        }
    }

    /**
     * Handle Z-Bon selection from history list.
     *
     * Opens the full Z-Bon detail view for the selected report.
     *
     * @param zbon Z-Bon summary to display
     */
    fun onZBonSelected(zbon: ZBonDto.ZBonSummeryDto) {
        logger.i { "ZBonViewModel.onZBonSelected: selecting zbonId=${zbon.id}" }
        viewModelScope.launch {
            runCatchingApi { zBonApiClient.getById(zbon.id) }
                .onSuccess { resp ->
                    _zBonState.update { it.copy(selectedZBon = resp) }
                }.onError { uiError ->
                    logger.e { "ZBonViewModel.onZBonSelected: failed to load zbonId=${zbon.id}" }
                    _zBonState.update { it.copy(error = uiError) }
                }
        }
    }

    /**
     * Print a Z-Bon report to the configured thermal printer.
     *
     * @param zbonId ID of the Z-Bon to print
     */
    fun printZBon(zbonId: Long) {
        logger.i { "ZBonViewModel.printZBon: printing zbonId=$zbonId" }
        viewModelScope.launch {
            runCatchingApi { zBonApiClient.printZBon(zbonId) }
                .onSuccess {
                    logger.i { "ZBonViewModel.printZBon: printed successfully" }
                    _zBonState.update { it.copy(successMessage = localized(ReportsKeys.zBonPrinted)) }
                }.onError { uiError ->
                    logger.e { "ZBonViewModel.printZBon: failed for zbonId=$zbonId, error=$uiError" }
                    _zBonState.update { it.copy(error = uiError) }
                }
        }
    }

    /**
     * Load Z-Bon PDF bytes for viewing in DocumentViewerPanel.
     *
     * @param zbonId ID of the Z-Bon to view as PDF
     */
    fun viewZBonPdf(zbonId: Long) {
        logger.i { "ZBonViewModel.viewZBonPdf: downloading PDF for zbonId=$zbonId" }
        viewModelScope.launch {
            runCatchingApi { zBonApiClient.downloadZBonPdf(zbonId) }
                .onSuccess { bytes ->
                    logger.i { "ZBonViewModel.viewZBonPdf: downloaded PDF, size=${bytes.size} bytes" }
                    _zBonState.update {
                        it.copy(
                            pdfBytes = bytes,
                            pdfFileName = "ZBon_$zbonId.pdf",
                        )
                    }
                }.onError { uiError ->
                    logger.e { "ZBonViewModel.viewZBonPdf: failed for zbonId=$zbonId, error=$uiError" }
                    _zBonState.update { it.copy(error = uiError) }
                }
        }
    }

    /**
     * Opens the payout dialog for one employee of the shown Z-Bon, pre-filled with their whole open
     * amount in the till's money-input convention — comma decimal, no grouping, no symbol, as the
     * Z-Bon counted-cash field prefills — so it round-trips through [TipPayoutAmountCheck]. Rounded
     * DOWN to cents, so a sub-cent remainder can never prefill more than is open. Does nothing when nothing is open — the panel hides the action then, so this only
     * guards a stale tap.
     */
    fun openTipPayout(
        zBonId: Long,
        status: ZBonDto.EmployeeTipPayoutStatusDto,
    ) {
        val open = Decimal.parseOrNull(status.remaining) ?: return
        if (open <= Decimal.ZERO) return
        _zBonState.update {
            it.copy(
                tipPayoutDraft =
                    TipPayoutDraft(
                        zBonId = zBonId,
                        employeeId = status.employeeId,
                        employeeName = status.employeeName,
                        remaining = status.remaining,
                        amount = open.format(TIP_PAYOUT_PREFILL),
                    ),
            )
        }
    }

    /** F59: cash from the drawer, or "paid via bank/payroll" (no cash, no Kassenbuch entry). Not while in flight. */
    fun setTipPayoutMethod(method: TipPayoutMethod) {
        _zBonState.update { state ->
            val draft = state.tipPayoutDraft ?: return@update state
            if (draft.isSubmitting) state else state.copy(tipPayoutDraft = draft.copy(method = method))
        }
    }

    /** The operator edited the payout amount. Ignored while the request is in flight. */
    fun updateTipPayoutAmount(text: String) {
        _zBonState.update { state ->
            val draft = state.tipPayoutDraft ?: return@update state
            if (draft.isSubmitting) state else state.copy(tipPayoutDraft = draft.copy(amount = text))
        }
    }

    /** Closes the payout dialog without booking anything. Not while the request is in flight. */
    fun dismissTipPayout() {
        _zBonState.update { state ->
            if (state.tipPayoutDraft?.isSubmitting == true) state else state.copy(tipPayoutDraft = null)
        }
    }

    /**
     * Books the drafted payout. Refused locally — no request — unless the amount passes
     * [TipPayoutAmountCheck]; the server repeats every check under its lock. On success the shown
     * Z-Bon's position for that employee is replaced by the server's answer, so the open amount
     * drops (and the action disappears once nothing is left) without reloading the closing.
     *
     * @param currencyCode the till's display currency, for the success message only
     */
    fun confirmTipPayout(currencyCode: String) {
        val draft = _zBonState.value.tipPayoutDraft ?: return
        if (draft.isSubmitting || draft.check != TipPayoutAmountCheck.VALID) return
        val amount = draft.amount.toCanonicalMoneyOrNull() ?: return
        _zBonState.update { it.copy(tipPayoutDraft = draft.copy(isSubmitting = true)) }
        viewModelScope.launch {
            runCatchingApi {
                zBonApiClient.payOutTip(
                    draft.zBonId,
                    ZBonDto.CreateTipPayoutRequestDto(employeeId = draft.employeeId, amount = amount, method = draft.method),
                )
            }.onSuccess { response ->
                logger.i { "ZBonViewModel.confirmTipPayout: booked ${response.belegNr} for zbonId=${draft.zBonId}" }
                val message =
                    getString(
                        TipPayoutKeys.success,
                        languageManager.currentLanguage.value,
                        formatMoneyForDisplay(amount, currencyCode),
                        draft.employeeName,
                    )
                _zBonState.update { state ->
                    state.copy(
                        tipPayoutDraft = null,
                        selectedZBon = state.selectedZBon?.withTipStatus(draft.zBonId, response.status),
                        createdZBon = state.createdZBon?.withTipStatus(draft.zBonId, response.status),
                        successMessage = message,
                    )
                }
            }.onError { uiError ->
                logger.e { "ZBonViewModel.confirmTipPayout: failed for zbonId=${draft.zBonId}, error=$uiError" }
                _zBonState.update { state ->
                    state.copy(
                        tipPayoutDraft = state.tipPayoutDraft?.copy(isSubmitting = false),
                        error = uiError,
                    )
                }
            }
        }
    }

    /** Opens the storno dialog for one booked payout. Ignored for a payout already reversed. */
    fun openTipReversal(
        zBonId: Long,
        status: ZBonDto.EmployeeTipPayoutStatusDto,
        payout: ZBonDto.TipPayoutRecordDto,
    ) {
        if (payout.reversed) return
        _zBonState.update {
            it.copy(
                tipReversalDraft =
                    TipReversalDraft(
                        zBonId = zBonId,
                        employeeId = status.employeeId,
                        employeeName = status.employeeName,
                        payout = payout,
                    ),
            )
        }
    }

    /** The operator edited the storno reason. Ignored while the request is in flight. */
    fun updateTipReversalReason(text: String) {
        _zBonState.update { state ->
            val draft = state.tipReversalDraft ?: return@update state
            if (draft.isSubmitting) state else state.copy(tipReversalDraft = draft.copy(reason = text))
        }
    }

    /** Closes the storno dialog without booking anything. Not while the request is in flight. */
    fun dismissTipReversal() {
        _zBonState.update { state ->
            if (state.tipReversalDraft?.isSubmitting == true) state else state.copy(tipReversalDraft = null)
        }
    }

    /**
     * Books the drafted storno. Refused locally without a reason or for a reversed payout; on success
     * the employee's position is replaced by the server's answer — the payout shows as reversed and
     * its amount is open again.
     *
     * @param currencyCode the till's display currency, for the success message only
     */
    fun confirmTipReversal(currencyCode: String) {
        val draft = _zBonState.value.tipReversalDraft ?: return
        if (!draft.canConfirm) return
        _zBonState.update { it.copy(tipReversalDraft = draft.copy(isSubmitting = true)) }
        viewModelScope.launch {
            runCatchingApi {
                val request = ZBonDto.ReverseTipPayoutRequestDto(reason = draft.reason.trim())
                val headerId = draft.payout.kassenbuchHeaderId
                // F59: a bank/payroll settlement has no Kassenbuch entry and is reversed by its row id.
                if (draft.payout.method == TipPayoutMethod.BANK_PAYROLL || headerId == null) {
                    zBonApiClient.reverseTipSettlement(draft.zBonId, draft.payout.payoutId, request)
                } else {
                    zBonApiClient.reverseTipPayout(draft.zBonId, headerId, request)
                }
            }.onSuccess { response ->
                logger.i { "ZBonViewModel.confirmTipReversal: booked ${response.belegNr} for zbonId=${draft.zBonId}" }
                val message =
                    getString(
                        TipPayoutKeys.reverseSuccess,
                        languageManager.currentLanguage.value,
                        formatMoneyForDisplay(draft.payout.amount, currencyCode),
                        draft.employeeName,
                    )
                _zBonState.update { state ->
                    state.copy(
                        tipReversalDraft = null,
                        selectedZBon = state.selectedZBon?.withTipStatus(draft.zBonId, response.status),
                        createdZBon = state.createdZBon?.withTipStatus(draft.zBonId, response.status),
                        successMessage = message,
                    )
                }
            }.onError { uiError ->
                logger.e { "ZBonViewModel.confirmTipReversal: failed for zbonId=${draft.zBonId}, error=$uiError" }
                _zBonState.update { state ->
                    state.copy(
                        tipReversalDraft = state.tipReversalDraft?.copy(isSubmitting = false),
                        error = uiError,
                    )
                }
            }
        }
    }

    private fun ZBonDto.ZBonResponseDto.withTipStatus(
        zBonId: Long,
        status: ZBonDto.EmployeeTipPayoutStatusDto,
    ): ZBonDto.ZBonResponseDto =
        if (id != zBonId) {
            this
        } else {
            copy(tipPayouts = tipPayouts.map { if (it.employeeId == status.employeeId) status else it })
        }

    /**
     * Clear PDF bytes when the viewer is closed.
     */
    fun clearPdfBytes() {
        _zBonState.update { currentState ->
            currentState.copy(pdfBytes = null, pdfFileName = null)
        }
    }
}
