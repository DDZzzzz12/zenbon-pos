package org.example.project.features.dashboard.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.core.auth.SettingManager
import org.example.project.core.data.models.PaymentType
import org.example.project.core.data.provider.PaymentTypeDataProvider
import org.example.project.core.data.remote.BillApiClient
import org.example.project.core.data.remote.SalesInvoiceApiClient
import org.example.project.core.data.remote.VoucherApiClient
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.models.setup.VoucherConfig
import org.example.project.core.domain.models.setup.VoucherSource
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.features.dashboard.presentation.components.billing.acceptsRebook
import org.example.project.features.dashboard.presentation.state.PaymentEntry
import org.example.project.features.dashboard.presentation.state.StornoRestoreConflict
import org.example.project.features.dashboard.presentation.state.ValidatedVoucherInfo
import org.example.project.money.Decimal
import org.example.project.money.MoneyRounding

/**
 * ViewModel for BillReceiptPanel operations.
 *
 * Handles bill receipt actions including deletion and printing.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * ViewModel managing bill receipt operations.
 *
 * Provides functions for deleting and printing bills,
 * with state management for loading and error handling.
 */
class BillReceiptViewModel(
    private val billApiClient: BillApiClient,
    private val salesInvoiceApiClient: SalesInvoiceApiClient,
    private val paymentTypeDataProvider: PaymentTypeDataProvider,
    private val voucherApiClient: VoucherApiClient,
    private val settingManager: SettingManager,
) : ViewModel() {
    private val logger = Logger.Companion.withTag("BillReceiptViewModel")

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<UiError?>(null)
    val error: StateFlow<UiError?> = _error.asStateFlow()

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    private val _billDeleted = MutableStateFlow(false)
    val billDeleted: StateFlow<Boolean> = _billDeleted.asStateFlow()

    /** The bill the last successful Storno reversed; the host reads the new Storno Beleg through it. */
    var lastVoidedBillId: Long? = null
        private set

    /** Set when a re-opening Storno was refused because new guests occupy the table. */
    private val _stornoRestoreConflict = MutableStateFlow<StornoRestoreConflict?>(null)
    val stornoRestoreConflict: StateFlow<StornoRestoreConflict?> = _stornoRestoreConflict.asStateFlow()

    private val _generatedPdfBytes = MutableStateFlow<ByteArray?>(null)
    val generatedPdfBytes: StateFlow<ByteArray?> = _generatedPdfBytes.asStateFlow()

    private val _issuedInvoiceNumber = MutableStateFlow<String?>(null)
    val issuedInvoiceNumber: StateFlow<String?> = _issuedInvoiceNumber.asStateFlow()

    /**
     * billId → id of the invoice this view model issued for it. The bill snapshot a dialog holds
     * does not learn of the new invoice, so without this the just-billed dialog still offered
     * *Rechnung erstellen* and a second tap met SALES_INVOICE_BILL_ALREADY_INVOICED (emu20 NOTE-q).
     * Never cleared with the PDF: the invoice stays issued.
     */
    private val _invoicedBills = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val invoicedBills: StateFlow<Map<Long, Long>> = _invoicedBills.asStateFlow()

    /** The invoice's remaining open amount after a successful till settlement (4c-2c). */
    private val _settledOpenAmount = MutableStateFlow<String?>(null)
    val settledOpenAmount: StateFlow<String?> = _settledOpenAmount.asStateFlow()

    /** The Neubuchung a successful Zahlartwechsel booked; the overlay hands it to its host, then clears it. */
    private val _rebookedBill = MutableStateFlow<BillDto.BillResponseDto?>(null)
    val rebookedBill: StateFlow<BillDto.BillResponseDto?> = _rebookedBill.asStateFlow()

    /**
     * The bill whose Zahlartwechsel was refused or failed. The bill on screen may be stale (already
     * stornoed elsewhere) or the rebook may even have committed before a TSE failure, so the overlay
     * has its host re-read it, then clears this.
     */
    private val _rebookFailedBillId = MutableStateFlow<Long?>(null)
    val rebookFailedBillId: StateFlow<Long?> = _rebookFailedBillId.asStateFlow()

    /**
     * The bill whose Storno was refused or failed (e.g. `BILL_ALREADY_DELETED`: stornoed or rebooked
     * on another device). The overlay has its host re-read the bill and its list, then clears this.
     * Not set for the occupied-table conflict, which is a choice still open to the operator.
     */
    private val _stornoFailedBillId = MutableStateFlow<Long?>(null)
    val stornoFailedBillId: StateFlow<Long?> = _stornoFailedBillId.asStateFlow()

    private val _paymentTypes = MutableStateFlow<List<PaymentType>>(emptyList())
    val paymentTypes: StateFlow<List<PaymentType>> = _paymentTypes.asStateFlow()

    private val _voucherConfig = MutableStateFlow<VoucherConfig?>(null)
    val voucherConfig: StateFlow<VoucherConfig?> = _voucherConfig.asStateFlow()

    private val _enabledVoucherSources = MutableStateFlow<List<VoucherSource>>(emptyList())
    val enabledVoucherSources: StateFlow<List<VoucherSource>> = _enabledVoucherSources.asStateFlow()

    private val _isValidatingVoucher = MutableStateFlow(false)
    val isValidatingVoucher: StateFlow<Boolean> = _isValidatingVoucher.asStateFlow()

    private val _validatedVoucher = MutableStateFlow<ValidatedVoucherInfo?>(null)
    val validatedVoucher: StateFlow<ValidatedVoucherInfo?> = _validatedVoucher.asStateFlow()

    private val _voucherValidationError = MutableStateFlow<String?>(null)
    val voucherValidationError: StateFlow<String?> = _voucherValidationError.asStateFlow()

    /**
     * Loads payment types and voucher configuration needed by the Zahlartwechsel dialog.
     * Call when the dialog opens.
     */
    fun loadPaymentChangeData() {
        viewModelScope.launch {
            runCatchingApi { paymentTypeDataProvider.getActivePaymentTypes() }
                .onSuccess { _paymentTypes.value = it }
                .onError { uiError -> _error.value = uiError }

            val config = settingManager.loadVoucherConfig()
            _voucherConfig.value = config
            _enabledVoucherSources.value = config.sources.filter { it.enabled }
        }
    }

    /**
     * Validate a voucher code and requested amount.
     *
     * Calls the API to verify the voucher exists, is active, and has sufficient balance.
     *
     * @param voucherNumber Voucher code to validate
     * @param requestedAmount Amount to redeem (optional)
     * @param sourceType Voucher source type for tracking
     */
    fun validateVoucher(
        voucherNumber: String,
        requestedAmount: Decimal?,
        sourceType: VoucherSourceType,
    ) {
        if (voucherNumber.isBlank()) {
            _voucherValidationError.value = "Voucher number is required"
            return
        }

        viewModelScope.launch {
            _isValidatingVoucher.value = true
            _voucherValidationError.value = null
            _validatedVoucher.value = null

            try {
                val request =
                    VoucherDto.ValidateVoucherRequestDto(
                        voucherNumber = voucherNumber.trim().uppercase(),
                        requestedAmount = requestedAmount?.toPlainString(),
                    )

                runCatchingApi { voucherApiClient.validateVoucher(request) }
                    .onSuccess { response ->
                        if (response.isValid && response.isActive) {
                            val validated =
                                ValidatedVoucherInfo(
                                    voucherNumber = response.voucherNumber,
                                    originalValue = Decimal.parse(response.originalValue),
                                    remainingBalance = Decimal.parse(response.remainingValue),
                                    source = sourceType,
                                    canRedeemAmount =
                                        if (response.canRedeemRequested) {
                                            requestedAmount ?: Decimal.parse(response.remainingValue)
                                        } else {
                                            Decimal.parse(response.remainingValue)
                                        },
                                    expiryDate = response.expiryDate,
                                )

                            _isValidatingVoucher.value = false
                            _validatedVoucher.value = validated
                            _voucherValidationError.value = null
                        } else {
                            _isValidatingVoucher.value = false
                            _voucherValidationError.value = response.validationMessage
                        }
                    }.onError { uiError ->
                        _isValidatingVoucher.value = false
                        _voucherValidationError.value = "Failed to validate voucher: $uiError"
                    }
            } catch (e: Exception) {
                logger.e(e) { "BillReceiptViewModel.validateVoucher: error validating voucher" }
                _isValidatingVoucher.value = false
                _voucherValidationError.value = "Error: ${e.message}"
            }
        }
    }

    /**
     * Clear voucher validation state.
     */
    fun clearVoucherValidation() {
        _validatedVoucher.value = null
        _voucherValidationError.value = null
        _isValidatingVoucher.value = false
    }

    /**
     * Zahlartwechsel: storno [bill] and book it again with [entries] and the whole [tip].
     *
     * Sends nothing unless the tender is well formed, balances the bill total plus the tip, actually
     * changes something and lowers the tip only when [mayLowerTip] (`BILL_VOID`) — the same
     * [RebookTender] rule the server refuses on (rule 8). On success [rebookedBill] holds the NEW
     * bill; a refusal lands in [error] and names the bill in [rebookFailedBillId].
     */
    fun rebookBill(
        bill: BillDto.BillResponseDto,
        entries: List<PaymentEntry>,
        tip: Decimal,
        mayLowerTip: Boolean,
    ) {
        val request = rebookRequestOrNull(bill, entries, tip, mayLowerTip)
        if (request == null) {
            logger.w { "Not sending a rebook of bill ${bill.billId}: the tender does not balance or changes nothing" }
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            runCatchingApi { billApiClient.rebookBill(bill.billId, request) }
                .onSuccess { newBill ->
                    logger.i { "Bill ${bill.billId} rebooked as ${newBill.billNumber}" }
                    _rebookedBill.value = newBill
                }.onError { uiError ->
                    logger.e { "Failed to rebook bill ${bill.billId}: $uiError" }
                    _error.value = uiError
                    _rebookFailedBillId.value = bill.billId
                }
            _isLoading.value = false
        }
    }

    /** Clear the rebooked bill after the overlay has handed it on. */
    fun clearRebookedBill() {
        _rebookedBill.value = null
    }

    /** Clear [rebookFailedBillId] after the overlay has had the bill re-read. */
    fun clearRebookFailed() {
        _rebookFailedBillId.value = null
    }

    private fun rebookRequestOrNull(
        bill: BillDto.BillResponseDto,
        entries: List<PaymentEntry>,
        tip: Decimal,
        mayLowerTip: Boolean,
    ): BillDto.RebookRequestDto? {
        if (!bill.acceptsRebook(entries, tip, mayLowerTip)) return null
        return BillDto.RebookRequestDto(
            payments =
                entries.map {
                    PaymentDto.PaymentRequest(amount = it.amount.toPlainString().trim(), paymentTypeId = it.paymentTypeId)
                },
            voucherRedeem =
                entries.filter { it.voucherNumber != null }.map {
                    VoucherDto.RedeemVoucherRequestDto(
                        voucherNumber = it.voucherNumber ?: throw IllegalStateException("Voucher number missing"),
                        redeemedAmount = it.amount.toPlainString().trim(),
                        voucherSource = it.voucherSource ?: throw IllegalStateException("Voucher source missing"),
                    )
                },
            tips = tip.setScale(2, MoneyRounding.DOWN).toPlainString(),
        )
    }

    /**
     * Delete a bill with optional deletion reason.
     *
     * Performs a soft delete on the bill, marking it as deleted
     * while preserving the record for audit purposes.
     *
     * @param billId The bill ID to delete
     * @param deletingReason Optional reason for deletion
     * @param restoreSession Re-open the bill's table session for re-billing. When the server refuses
     *   that because new guests occupy the table, [stornoRestoreConflict] is set instead of [error].
     */
    fun deleteBill(
        billId: Long,
        deletingReason: String? = null,
        restoreSession: Boolean = true,
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            logger.i { "Deleting bill: $billId with reason: $deletingReason, restoreSession=$restoreSession" }

            runCatchingApi { billApiClient.deleteBill(billId, deletingReason, restoreSession) }
                .onSuccess {
                    logger.i { "Bill deleted successfully: $billId" }
                    _successMessage.value = "Bill deleted successfully"
                    lastVoidedBillId = billId
                    _billDeleted.value = true
                }.onError { uiError ->
                    if (restoreSession && uiError.code == PosErrorCode.TABLE_HAS_ACTIVE_SESSION) {
                        logger.w { "Storno of bill $billId cannot re-open its table: new guests occupy it" }
                        _stornoRestoreConflict.value = StornoRestoreConflict(billId, deletingReason)
                    } else {
                        logger.e { "Failed to delete bill: $uiError" }
                        _error.value = uiError
                        _stornoFailedBillId.value = billId
                    }
                }
            _isLoading.value = false
        }
    }

    /** Clear [stornoFailedBillId] after the overlay has had the bill re-read. */
    fun clearStornoFailed() {
        _stornoFailedBillId.value = null
    }

    /** Retries the refused Storno without re-opening the table (the new guests keep it). */
    fun confirmStornoWithoutRestore() {
        val conflict = _stornoRestoreConflict.value ?: return
        _stornoRestoreConflict.value = null
        deleteBill(conflict.billId, conflict.deletingReason, restoreSession = false)
    }

    fun dismissStornoRestoreConflict() {
        _stornoRestoreConflict.value = null
    }

    /**
     * Print the bill receipt.
     * This will handle sending the bill to a printer or generating a PDF.
     *
     * @param billId The bill ID to print
     */
    fun printBill(
        billId: Long,
        bewirtungsbeleg: Boolean = false,
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            logger.i { "Printing bill: $billId, bewirtungsbeleg=$bewirtungsbeleg" }

            runCatchingApi { billApiClient.printBill(billId, bewirtungsbeleg) }
                .onSuccess {
                    logger.i { "Bill printed successfully: $billId" }
                    _successMessage.value = "Bill printed successfully"
                }.onError { uiError ->
                    logger.e { "Failed to print bill: $uiError" }
                    _error.value = uiError
                }
            _isLoading.value = false
        }
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _error.value = null
    }

    /**
     * Clear success message.
     */
    fun clearSuccess() {
        _successMessage.value = null
    }

    /**
     * Reset deletion state.
     */
    fun resetDeletionState() {
        _billDeleted.value = false
    }

    /**
     * Takes a payment at the counter against this bill's open receivable (4c-2c).
     *
     * The server owns every rule — issued, right origin, settling tender, no overpayment — so this
     * only reports what came back: the invoice's reduced open amount, or the error.
     */
    fun settleReceivable(
        invoiceId: Long,
        request: SalesInvoiceDto.SettleAtTillRequestDto,
    ) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null
                runCatchingApi { salesInvoiceApiClient.settleAtTill(invoiceId, request) }
                    .onSuccess { invoice ->
                        logger.i { "Settled receivable $invoiceId at the till, open now ${invoice.openAmount}" }
                        _settledOpenAmount.value = invoice.openAmount
                    }.onError { uiError ->
                        logger.e { "Failed to settle receivable $invoiceId: $uiError" }
                        _error.value = uiError
                    }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Clear the settled-open-amount state after its message has been consumed. */
    fun clearSettledOpenAmount() {
        _settledOpenAmount.value = null
    }

    /**
     * Clear generated PDF bytes.
     */
    fun clearGeneratedPdf() {
        _generatedPdfBytes.value = null
        _issuedInvoiceNumber.value = null
    }

    /**
     * Creates, issues and fetches the invoice for a settled bill — the till's whole invoice action.
     *
     * Three calls rather than one, because each is a distinct legal step: the draft snapshots the
     * bill's items, VAT split and TSE block; issuing consumes a §14 UStG number and archives the
     * PDF; the PDF read serves that archived file rather than a re-render, so the bytes match the
     * copy the guest walks out with. Every guard — deleted, training, already invoiced, totals
     * disagreeing with the receipt — lives on the server; this only reports what came back.
     */
    fun createAndIssueInvoice(request: SalesInvoiceDto.CreateFromBillRequestDto) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null
                logger.i { "Creating invoice draft for bill: ${request.billId}" }

                val draft =
                    runCatchingApi { salesInvoiceApiClient.createFromBill(request) }
                        .onError { uiError ->
                            logger.e { "Failed to create invoice draft for bill ${request.billId}: $uiError" }
                            _error.value = uiError
                        }.getOrNull() ?: return@launch

                val issued =
                    runCatchingApi { salesInvoiceApiClient.issue(draft.id) }
                        .onError { uiError ->
                            logger.e { "Failed to issue invoice ${draft.id}: $uiError" }
                            _error.value = uiError
                        }.getOrNull() ?: return@launch

                logger.i { "Issued invoice ${issued.id} as ${issued.invoiceNumber}" }
                _invoicedBills.update { it + (request.billId to issued.id) }
                _issuedInvoiceNumber.value = issued.invoiceNumber
                loadInvoicePdf(issued.id)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Shows the invoice a bill already carries. Serves the archived file; it is never re-rendered,
     * so a later template change cannot alter what an issued invoice looks like.
     */
    fun showInvoice(invoiceId: Long) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null
                loadInvoicePdf(invoiceId)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun loadInvoicePdf(invoiceId: Long) {
        runCatchingApi { salesInvoiceApiClient.pdf(invoiceId) }
            .onSuccess { bytes -> _generatedPdfBytes.value = bytes }
            .onError { uiError ->
                logger.e { "Failed to load the archived PDF of invoice $invoiceId: $uiError" }
                _error.value = uiError
            }
    }
}
