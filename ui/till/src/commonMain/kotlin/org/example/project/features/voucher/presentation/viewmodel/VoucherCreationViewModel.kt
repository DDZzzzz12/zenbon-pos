/**
 * ViewModel for voucher creation.
 *
 * Manages state and business logic for creating vouchers in two modes:
 * 1. Counter sale: voucher sold directly at the counter, paid immediately
 * 2. Table Session: Adding voucher to existing table order
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.voucher.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.core.data.models.PaymentType
import org.example.project.core.data.provider.PaymentTypeDataProvider
import org.example.project.core.data.remote.BillApiClient
import org.example.project.core.data.remote.EcrConfigurationApiClient
import org.example.project.core.data.remote.VoucherApiClient
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.VoucherKeys
import org.example.project.features.voucher.presentation.state.CounterSaleVoucherState
import org.example.project.features.voucher.presentation.state.TableVoucherState
import org.example.project.features.voucher.presentation.state.VoucherCreationState
import org.example.project.money.Decimal

/**
 * ViewModel for managing voucher creation state and operations.
 *
 * @property billApiClient Prints the counter sale's receipt from the bill it was settled on
 * @property shopConfigurationApiClient Reads the shop's print-settings, which decide whether the
 *   receipt print is offered
 * @property shopId The paired shop's id, or null before pairing
 */
class VoucherCreationViewModel(
    private val voucherApiClient: VoucherApiClient,
    private val paymentTypeDataProvider: PaymentTypeDataProvider,
    private val billApiClient: BillApiClient,
    private val ecrConfigurationApiClient: EcrConfigurationApiClient,
    private val shopId: () -> Long?,
) : ViewModel() {
    private val logger = Logger.withTag("VoucherCreationViewModel")

    private val _uiState = MutableStateFlow(VoucherCreationState())
    val uiState: StateFlow<VoucherCreationState> = _uiState.asStateFlow()

    // ===== VOUCHER SYSTEM CONFIGURATION =====

    /**
     * Loads available payment types from the data provider.
     *
     * Retrieves active payment types via the DataProvider abstraction, which
     * reads from the local cache on Android/Desktop or via the API accessor on Desktop.
     */
    fun loadPaymentTypes() {
        viewModelScope.launch {
            logger.i { "VoucherCreationViewModel.loadPaymentTypes: loading payment types" }
            _uiState.update { it.copy(isLoadingPaymentTypes = true) }

            runCatchingApi { paymentTypeDataProvider.getActivePaymentTypes() }
                .onSuccess { types ->
                    logger.i { "VoucherCreationViewModel.loadPaymentTypes: loaded ${types.size} payment types" }
                    _uiState.update {
                        it.copy(paymentTypes = types, isLoadingPaymentTypes = false)
                    }
                }.onError { uiError ->
                    logger.e { "VoucherCreationViewModel.loadPaymentTypes: failed to load payment types: $uiError" }
                    _uiState.update {
                        it.copy(isLoadingPaymentTypes = false, systemError = uiError)
                    }
                }
        }
    }

    /**
     * Read the shop's print-settings: with printing disabled the receipt print is not offered, since
     * the server refuses it (409 `PRINTING_DISABLED`) — the same rule as „Referenzdruck“ (SET-04).
     * Read on every dialog open, so a toggle made during the shift applies without a restart. An
     * unreadable setting leaves the action offered; the server's refusal still explains it.
     */
    fun loadPrintAvailability() {
        val shop = shopId() ?: return
        viewModelScope.launch {
            runCatchingApi { ecrConfigurationApiClient.getPrintSettings(shop) }
                .onSuccess { settings -> _uiState.update { it.copy(printOffered = settings.enabled) } }
                .onError { uiError -> logger.w { "VoucherCreationViewModel: print-settings not read: $uiError" } }
        }
    }

    // ===== COUNTER SALE VOUCHER =====

    /**
     * Updates voucher value for the counter sale.
     */
    fun setCounterSaleValue(value: String) {
        val errors =
            _uiState.value.counterSale.errors
                .toMutableMap()

        // Validate value
        if (value.isNotBlank() && Decimal.parseOrNull(value)?.let { it > Decimal.ZERO } != true) {
            errors["voucherValue"] = VoucherKeys.valueMustBePositive
        } else {
            errors.remove("voucherValue")
        }

        _uiState.update {
            it.copy(
                counterSale =
                    it.counterSale.copy(
                        voucherValue = value,
                        errors = errors,
                    ),
            )
        }
    }

    /**
     * Selects payment type for the counter sale.
     */
    fun selectCounterSalePaymentType(paymentType: PaymentType) {
        _uiState.update {
            it.copy(
                counterSale = it.counterSale.copy(selectedPaymentType = paymentType),
            )
        }
    }

    /**
     * Updates tip amount for the counter sale.
     */
    fun setCounterSaleTip(tip: String) {
        val errors =
            _uiState.value.counterSale.errors
                .toMutableMap()

        // Validate tip
        if (tip.isNotBlank() && Decimal.parseOrNull(tip)?.isNegative != false) {
            errors["tipAmount"] = VoucherKeys.tipMustBeNonNegative
        } else {
            errors.remove("tipAmount")
        }

        _uiState.update {
            it.copy(
                counterSale =
                    it.counterSale.copy(
                        tipAmount = tip,
                        errors = errors,
                    ),
            )
        }
    }

    /**
     * Creates a counter sale voucher.
     */
    fun createCounterSaleVoucher() {
        val state = _uiState.value.counterSale

        // Validate
        if (!state.isValid) {
            _uiState.update {
                it.copy(
                    counterSale =
                        it.counterSale.copy(
                            errorMessage =
                                UiError.Unknown(
                                    titleKey = CommonKeys.unknownErrorTitle,
                                    messageKey = CommonKeys.unknownErrorMessage,
                                ),
                        ),
                )
            }
            return
        }

        viewModelScope.launch {
            logger.i { "VoucherCreationViewModel.createCounterSaleVoucher: creating counter sale voucher value=${state.voucherValue}" }
            _uiState.update { it.copy(counterSale = it.counterSale.copy(isLoading = true)) }

            val request =
                VoucherDto.CreateVoucherRequestDto(
                    sessionId = null,
                    originalValue = state.voucherValue,
                    paymentTypeId = state.selectedPaymentType?.id,
                    // Sent canonical; the server bills it as the sale's tip (T5-3) — the dialog's
                    // total already shows value + tip, so both sides must agree on it.
                    tipAmount = Decimal.parseOrNull(state.tipAmount)?.takeIf { it > Decimal.ZERO }?.toPlainString(),
                )

            runCatchingApi { voucherApiClient.createVoucher(request) }
                .onSuccess { resp ->
                    logger.i { "VoucherCreationViewModel.createCounterSaleVoucher: voucher created voucherNumber=${resp.voucherNumber}" }
                    _uiState.update {
                        it.copy(
                            counterSale =
                                it.counterSale.copy(
                                    isLoading = false,
                                    createdVoucher = resp,
                                    showPrintDialog = true,
                                    errorMessage = null,
                                ),
                        )
                    }
                }.onError { uiError ->
                    logger.e { "VoucherCreationViewModel.createCounterSaleVoucher: failed: $uiError" }
                    _uiState.update {
                        it.copy(
                            counterSale =
                                it.counterSale.copy(
                                    isLoading = false,
                                    errorMessage = uiError,
                                ),
                        )
                    }
                }
        }
    }

    /**
     * Prints the counter sale's receipt: the sale is a signed Kassenbeleg, so the customer's receipt
     * is that bill, printed through the same `POST /api/v1/bills/{billId}/print` as any other bill
     * (F38). Refused here while printing is disabled or no bill is known (a table-session voucher is
     * billed later with its table); the server refuses the disabled case too (409 `PRINTING_DISABLED`).
     * Success sets [CounterSaleVoucherState.receiptPrinted]; a failure lands in
     * [CounterSaleVoucherState.printError] and leaves the dialog open, so the cashier can retry.
     */
    fun printVoucherReceipt() {
        val state = _uiState.value
        val billId = state.counterSale.createdVoucher?.billId ?: return
        if (!state.printOffered || state.counterSale.isPrinting) return

        viewModelScope.launch {
            logger.i { "VoucherCreationViewModel.printVoucherReceipt: printing billId=$billId" }
            _uiState.update { it.copy(counterSale = it.counterSale.copy(isPrinting = true, printError = null)) }
            runCatchingApi { billApiClient.printBill(billId) }
                .onSuccess {
                    _uiState.update { it.copy(counterSale = it.counterSale.copy(isPrinting = false, receiptPrinted = true)) }
                }.onError { uiError ->
                    logger.e { "VoucherCreationViewModel.printVoucherReceipt: failed: $uiError" }
                    _uiState.update { it.copy(counterSale = it.counterSale.copy(isPrinting = false, printError = uiError)) }
                }
        }
    }

    /** Clears a receipt-print failure once it has been shown. */
    fun clearPrintError() {
        _uiState.update { it.copy(counterSale = it.counterSale.copy(printError = null)) }
    }

    /**
     * Closes the success dialog and resets the counter sale form.
     */
    fun closePrintDialog() {
        _uiState.update {
            it.copy(
                counterSale =
                    CounterSaleVoucherState(
                        showPrintDialog = false,
                    ),
            )
        }
    }

    /**
     * Dismisses the counter sale dialog and resets state.
     */
    fun dismissCounterSaleDialog() {
        _uiState.update {
            it.copy(counterSale = CounterSaleVoucherState())
        }
    }

    // ===== TABLE VOUCHER =====

    /**
     * Initializes table voucher state for a specific session.
     */
    fun openTableVoucherDialog(
        sessionId: Long,
        tableName: String,
    ) {
        _uiState.update {
            it.copy(
                tableVoucher =
                    TableVoucherState(
                        sessionId = sessionId,
                        tableName = tableName,
                    ),
            )
        }
    }

    /**
     * Updates voucher value for table session.
     */
    fun setTableVoucherValue(value: String) {
        val currentState = _uiState.value.tableVoucher ?: return
        val errors = currentState.errors.toMutableMap()

        // Validate value
        if (value.isNotBlank() && Decimal.parseOrNull(value)?.let { it > Decimal.ZERO } != true) {
            errors["voucherValue"] = VoucherKeys.valueMustBePositive
        } else {
            errors.remove("voucherValue")
        }

        _uiState.update {
            it.copy(
                tableVoucher =
                    currentState.copy(
                        voucherValue = value,
                        errors = errors,
                    ),
            )
        }
    }

    /**
     * Creates a table session voucher.
     */
    fun createTableVoucher() {
        val state = _uiState.value.tableVoucher ?: return

        // Validate
        if (!state.isValid) {
            _uiState.update {
                it.copy(
                    tableVoucher =
                        state.copy(
                            errorMessage =
                                UiError.Unknown(
                                    titleKey = CommonKeys.unknownErrorTitle,
                                    messageKey = CommonKeys.unknownErrorMessage,
                                ),
                        ),
                )
            }
            return
        }

        viewModelScope.launch {
            logger.i {
                "VoucherCreationViewModel.createTableVoucher: creating table voucher sessionId=${state.sessionId} value=${state.voucherValue}"
            }
            _uiState.update {
                it.copy(tableVoucher = state.copy(isLoading = true))
            }

            val request =
                VoucherDto.CreateVoucherRequestDto(
                    sessionId = state.sessionId,
                    originalValue = state.voucherValue,
                )

            runCatchingApi { voucherApiClient.createVoucher(request) }
                .onSuccess { resp ->
                    logger.i { "VoucherCreationViewModel.createTableVoucher: table voucher created voucherNumber=${resp.voucherNumber}" }
                    _uiState.update {
                        it.copy(
                            tableVoucher =
                                state.copy(
                                    isLoading = false,
                                    createdVoucher = resp,
                                    isSuccess = true,
                                    errorMessage = null,
                                ),
                        )
                    }
                }.onError { uiError ->
                    logger.e { "VoucherCreationViewModel.createTableVoucher: failed: $uiError" }
                    _uiState.update {
                        it.copy(
                            tableVoucher =
                                state.copy(
                                    isLoading = false,
                                    errorMessage = uiError,
                                ),
                        )
                    }
                }
        }
    }

    /**
     * Dismisses table voucher dialog and resets state.
     */
    fun dismissTableVoucherDialog() {
        _uiState.update {
            it.copy(tableVoucher = null)
        }
    }

    // ===== HELPER FUNCTIONS =====

    /**
     * Clears any system-level errors.
     */
    fun clearSystemError() {
        _uiState.update { it.copy(systemError = null) }
    }
}
