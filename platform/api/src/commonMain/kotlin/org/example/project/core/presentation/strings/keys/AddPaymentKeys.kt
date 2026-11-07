package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object AddPaymentKeys {
    val dialogTitle = StringKey("add_payment.dialog_title")
    val paymentTypeLabel = StringKey("add_payment.payment_type_label")
    val selectPaymentType = StringKey("add_payment.select_payment_type")
    val amountLabel = StringKey("add_payment.amount_label")
    val amountPlaceholder = StringKey("add_payment.amount_placeholder")
    val remainingAmount = StringKey("add_payment.remaining_amount") // {0} = amount

    // getCancelButton delegates to CommonStrings — skipped
    val addButton = StringKey("add_payment.add_button")

    // Voucher Section
    val voucherSourceLabel = StringKey("add_payment.voucher_source_label")
    val voucherNumberLabel = StringKey("add_payment.voucher_number_label")
    val voucherNumberFormat = StringKey("add_payment.voucher_number_format")
    val validateVoucherButton = StringKey("add_payment.validate_voucher_button")
    val validateVoucherButtonValidating = StringKey("add_payment.validate_voucher_button_validating")
    val validationFailedTitle = StringKey("add_payment.validation_failed_title")
    val voucherValidTitle = StringKey("add_payment.voucher_valid_title")
    val voucherNumberFieldLabel = StringKey("add_payment.voucher_number_field_label")
    val voucherOriginalValueLabel = StringKey("add_payment.voucher_original_value_label")
    val voucherRemainingLabel = StringKey("add_payment.voucher_remaining_label")
    val autoTipLabel = StringKey("add_payment.auto_tip_label") // {0} = tipAmount

    // Quick Amount Buttons
    val quickAmountLabel = StringKey("add_payment.quick_amount_label")
    val fullLabel = StringKey("add_payment.full_label")
}
