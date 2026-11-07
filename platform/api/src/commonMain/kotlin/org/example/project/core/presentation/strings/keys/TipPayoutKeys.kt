package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

/** Paying a closing's employee tips out of the drawer, from the Z-Bon detail. */
object TipPayoutKeys {
    val sectionTitle = StringKey("tip_payout.section_title")
    val sectionHint = StringKey("tip_payout.section_hint")
    val tipTotal = StringKey("tip_payout.tip_total")
    val paidOut = StringKey("tip_payout.paid_out")
    val remaining = StringKey("tip_payout.remaining")
    val fullyPaid = StringKey("tip_payout.fully_paid")
    val payOutAction = StringKey("tip_payout.pay_out_action")
    val dialogTitle = StringKey("tip_payout.dialog_title")

    /** {0} = employee name, {1} = remaining amount. */
    val dialogMessage = StringKey("tip_payout.dialog_message")
    val amountLabel = StringKey("tip_payout.amount_label")
    val confirm = StringKey("tip_payout.confirm")
    val amountInvalid = StringKey("tip_payout.amount_invalid")

    /** {0} = remaining amount. */
    val amountExceedsRemaining = StringKey("tip_payout.amount_exceeds_remaining")

    /** {0} = amount, {1} = employee name. */
    val success = StringKey("tip_payout.success")
    val exceedsRemainingTitle = StringKey("tip_payout.exceeds_remaining_title")
    val exceedsRemainingMessage = StringKey("tip_payout.exceeds_remaining_message")
    val notAvailableTitle = StringKey("tip_payout.not_available_title")
    val notAvailableMessage = StringKey("tip_payout.not_available_message")

    // Storno of a booked payout
    val payoutLine = StringKey("tip_payout.payout_line")
    val reverseAction = StringKey("tip_payout.reverse_action")
    val reversedLabel = StringKey("tip_payout.reversed_label")
    val reverseDialogTitle = StringKey("tip_payout.reverse_dialog_title")

    /** {0} = amount, {1} = employee name, {2} = Beleg-Nr. */
    val reverseDialogMessage = StringKey("tip_payout.reverse_dialog_message")
    val reasonLabel = StringKey("tip_payout.reason_label")
    val reasonRequired = StringKey("tip_payout.reason_required")
    val reverseConfirm = StringKey("tip_payout.reverse_confirm")

    /** {0} = amount, {1} = employee name. */
    val reverseSuccess = StringKey("tip_payout.reverse_success")
    val alreadyReversedTitle = StringKey("tip_payout.already_reversed_title")
    val alreadyReversedMessage = StringKey("tip_payout.already_reversed_message")
    val notAPayoutTitle = StringKey("tip_payout.not_a_payout_title")
    val notAPayoutMessage = StringKey("tip_payout.not_a_payout_message")

    // Payout inside the "Z-Bon erstellen" dialog (F58)
    val closingSectionHint = StringKey("tip_payout.closing_section_hint")

    /** {0} = employee name, {1} = total, {2} = cash part, {3} = card part. */
    val closingRow = StringKey("tip_payout.closing_row")
    val closingAmountLabel = StringKey("tip_payout.closing_amount_label")

    /** F65: a checked row whose amount is empty or zero — pay something or untick the row. */
    val closingAmountMissing = StringKey("tip_payout.closing_amount_missing")

    // Bank/payroll settlement (F59, option C)
    val methodCash = StringKey("tip_payout.method_cash")
    val methodBankPayroll = StringKey("tip_payout.method_bank_payroll")
    val bankPayrollHint = StringKey("tip_payout.bank_payroll_hint")

    /** {0} = amount. */
    val bankPayrollRecord = StringKey("tip_payout.bank_payroll_record")

    // Top-up from another cash book in the "Z-Bon erstellen" dialog (F59, option A)

    /** {0} = shortfall. */
    val shortfall = StringKey("tip_payout.shortfall")

    /** {0} = cash book name. */
    val topUpFrom = StringKey("tip_payout.top_up_from")

    /** {0} = cash book name, {1} = its balance. */
    val topUpSource = StringKey("tip_payout.top_up_source")
    val topUpAmountLabel = StringKey("tip_payout.top_up_amount_label")
    val topUpNoSource = StringKey("tip_payout.top_up_no_source")

    /** {0} = shortfall. */
    val topUpTooLow = StringKey("tip_payout.top_up_too_low")
}
