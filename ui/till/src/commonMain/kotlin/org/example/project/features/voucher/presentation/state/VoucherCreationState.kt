/**
 * State classes for voucher creation UI.
 *
 * Defines UI state for different voucher creation scenarios:
 * - Counter sale vouchers (sold directly at the counter, no table session)
 * - Table session vouchers (adding to existing table orders)
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.voucher.presentation.state

import org.example.project.core.data.models.PaymentType
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.utils.toDecimalOrZero
import org.example.project.money.Decimal

/**
 * UI state for counter sale voucher creation.
 *
 * Represents a voucher sold directly at the counter and paid immediately.
 * Requires voucher value, payment type selection, and optional tip.
 */
data class CounterSaleVoucherState(
    /** Voucher value as string for text input */
    val voucherValue: String = "",
    /** Selected payment type for voucher purchase */
    val selectedPaymentType: PaymentType? = null,
    /** Optional tip amount as string for text input */
    val tipAmount: String = "0.00",
    /** Whether voucher creation is in progress */
    val isLoading: Boolean = false,
    /** Field-specific error messages */
    val errors: Map<String, StringKey> = emptyMap(),
    /** Typed error for API/validation failures (rendered via str(messageKey, ...) in screen) */
    val errorMessage: UiError? = null,
    /** Created voucher response (on success) */
    val createdVoucher: VoucherDto.VoucherResponseDto? = null,
    /** Whether to show print receipt confirmation dialog */
    val showPrintDialog: Boolean = false,
    /** Whether the sale's receipt is being printed */
    val isPrinting: Boolean = false,
    /** Typed error of a failed receipt print (shown via PosErrorWatcher; the dialog stays open) */
    val printError: UiError? = null,
    /** Set once the sale's receipt was printed; the dialog confirms it and closes */
    val receiptPrinted: Boolean = false,
) {
    /**
     * Computed total amount (voucher value + tip).
     */
    val totalAmount: Decimal
        get() = voucherValue.toDecimalOrZero() + tipAmount.toDecimalOrZero()

    /**
     * Whether the form is valid and ready to submit.
     */
    val isValid: Boolean
        get() =
            Decimal.parseOrNull(voucherValue)?.let { it > Decimal.ZERO } == true &&
                selectedPaymentType != null &&
                (tipAmount.isEmpty() || Decimal.parseOrNull(tipAmount)?.let { !it.isNegative } == true) &&
                errors.isEmpty()
}

/**
 * UI state for table session voucher creation.
 *
 * Represents adding a voucher to an existing table order.
 * Only requires voucher value (payment happens when table pays bill).
 */
data class TableVoucherState(
    /** Table session ID where voucher will be added */
    val sessionId: Long,
    /** Table number or name for display */
    val tableName: String = "",
    /** Voucher value as string for text input */
    val voucherValue: String = "",
    /** Whether voucher creation is in progress */
    val isLoading: Boolean = false,
    /** Field-specific error messages */
    val errors: Map<String, StringKey> = emptyMap(),
    /** Typed error for API/validation failures (rendered via str(messageKey, ...) in screen) */
    val errorMessage: UiError? = null,
    /** Created voucher response (on success) */
    val createdVoucher: VoucherDto.VoucherResponseDto? = null,
    /** Whether creation was successful */
    val isSuccess: Boolean = false,
) {
    /**
     * Whether the form is valid and ready to submit.
     */
    val isValid: Boolean
        get() = Decimal.parseOrNull(voucherValue)?.let { it > Decimal.ZERO } == true && errors.isEmpty()
}

/**
 * Combined voucher creation state for the ViewModel.
 *
 * Manages state for both counter sale and table voucher scenarios,
 * along with shared state like payment types list.
 */
data class VoucherCreationState(
    /** Available payment types for the counter sale */
    val paymentTypes: List<PaymentType> = emptyList(),
    /** Whether payment types are being loaded */
    val isLoadingPaymentTypes: Boolean = false,
    /** Counter sale voucher state */
    val counterSale: CounterSaleVoucherState = CounterSaleVoucherState(),
    /** Table voucher state */
    val tableVoucher: TableVoucherState? = null,
    /** Typed error for system-level issues (rendered via str(messageKey, ...) in screen) */
    val systemError: UiError? = null,
    /**
     * Whether the counter sale's receipt print is offered: false once the shop's print-settings are
     * read as disabled (the server refuses it with 409 `PRINTING_DISABLED`). Stays true while unknown
     * or unreadable — the server's refusal still explains it then.
     */
    val printOffered: Boolean = true,
)
