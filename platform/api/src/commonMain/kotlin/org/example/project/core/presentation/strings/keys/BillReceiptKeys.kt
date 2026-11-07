package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object BillReceiptKeys {
    val dialogTitle = StringKey("bill_receipt.dialog_title")
    val billNumberLabel = StringKey("bill_receipt.bill_number_label")
    val tableLabel = StringKey("bill_receipt.table_label")
    val employeeNumber = StringKey("bill_receipt.employee_number")
    val itemsLabel = StringKey("bill_receipt.items_label")
    val subtotalLabel = StringKey("bill_receipt.subtotal_label")
    val taxLabel = StringKey("bill_receipt.tax_label") // {0} = vatRate
    val tipLabel = StringKey("bill_receipt.tip_label")
    val totalLabel = StringKey("bill_receipt.total_label")
    val paymentsLabel = StringKey("bill_receipt.payments_label")
    val thankYouMessage = StringKey("bill_receipt.thank_you_message")

    // getCloseButton delegates to CommonStrings — skipped
    val printButton = StringKey("bill_receipt.print_button")
    val deleteButton = StringKey("bill_receipt.delete_button")
    val deleteConfirmationTitle = StringKey("bill_receipt.delete_confirmation_title")
    val deleteConfirmationMessage = StringKey("bill_receipt.delete_confirmation_message") // {0} = billNumber
    val deletionReasonLabel = StringKey("bill_receipt.deletion_reason_label")
    val deletionReasonPlaceholder = StringKey("bill_receipt.deletion_reason_placeholder")
    val confirmDeleteButton = StringKey("bill_receipt.confirm_delete_button")
    val restoreSessionLabel = StringKey("bill_receipt.restore_session_label")
    val restoreSessionHint = StringKey("bill_receipt.restore_session_hint")
    val restoreConflictTitle = StringKey("bill_receipt.restore_conflict_title")
    val restoreConflictMessage = StringKey("bill_receipt.restore_conflict_message")
    val stornoWithoutRestoreButton = StringKey("bill_receipt.storno_without_restore_button")

    // getCancelButton delegates to CommonStrings — skipped
    val successMessage = StringKey("bill_receipt.success_message")
    val billIdLabel = StringKey("bill_receipt.bill_id_label")
    val startTimeLabel = StringKey("bill_receipt.start_time_label")
    val endTimeLabel = StringKey("bill_receipt.end_time_label")
    val tseTransactionIdLabel = StringKey("bill_receipt.tse_transaction_id_label")
    val deletedBillIdLabel = StringKey("bill_receipt.deleted_bill_id_label")
    val deletingReasonLabel = StringKey("bill_receipt.deleting_reason_label")
    val voucherRedeemLabel = StringKey("bill_receipt.voucher_redeem_label")
    val voucherCodeLabel = StringKey("bill_receipt.voucher_code_label")
    val voucherAmountLabel = StringKey("bill_receipt.voucher_amount_label")
    val unitPriceLabel = StringKey("bill_receipt.unit_price_label")
    val extrasLabel = StringKey("bill_receipt.extras_label")
    val sideNotesLabel = StringKey("bill_receipt.side_notes_label")
    val statusLabel = StringKey("bill_receipt.status_label")
    val takeawayLabel = StringKey("bill_receipt.takeaway_label")
    val transferredLabel = StringKey("bill_receipt.transferred_label")
    val customPriceLabel = StringKey("bill_receipt.custom_price_label")
    val discountLabel = StringKey("bill_receipt.discount_label")
    val tipRecipientOwner = StringKey("bill_receipt.tip_recipient_owner")
    val tipRecipientEmployee = StringKey("bill_receipt.tip_recipient_employee")
    val taxIncluded = StringKey("bill_receipt.tax_included")
    val notTaxable = StringKey("bill_receipt.not_taxable")
    val createInvoiceButton = StringKey("bill_receipt.create_invoice_button")
    val createInvoiceButtonCompact = StringKey("bill_receipt.create_invoice_button_compact")
    val receiptTitle = StringKey("bill_receipt.receipt_title")
    val processing = StringKey("bill_receipt.processing")
    val printBewirtungsbeleg = StringKey("bill_receipt.print_bewirtungsbeleg")
    val vatRate = StringKey("bill_receipt.vat_rate")
    val vatAmount = StringKey("bill_receipt.vat_amount")
    val netAmount = StringKey("bill_receipt.net_amount")
    val grossAmount = StringKey("bill_receipt.gross_amount")
    val changePaymentButton = StringKey("bill_receipt.change_payment_button")
    val changePaymentTitle = StringKey("bill_receipt.change_payment_title")
    val changePaymentCurrent = StringKey("bill_receipt.change_payment_current")
    val changePaymentNew = StringKey("bill_receipt.change_payment_new")
    val changePaymentRemaining = StringKey("bill_receipt.change_payment_remaining") // {0} = remaining amount
    val changePaymentAddPayment = StringKey("bill_receipt.change_payment_add_payment")
    val changePaymentConfirm = StringKey("bill_receipt.change_payment_confirm")
    val changePaymentTip = StringKey("bill_receipt.change_payment_tip")
    val changePaymentTipInvalid = StringKey("bill_receipt.change_payment_tip_invalid")
    val rebookWarning = StringKey("bill_receipt.rebook_warning") // {0} = the bill number
    val rebookSuccess = StringKey("bill_receipt.rebook_success") // {0} = the Neubuchung's bill number

    // Links between related bills (Storno, cancelled original, clearing).
    // {0} = the linked bill's number.
    val relationStornoOf = StringKey("bill_receipt.relation_storno_of")
    val relationStornoRebookOf = StringKey("bill_receipt.relation_storno_rebook_of")
    val relationClearingOf = StringKey("bill_receipt.relation_clearing_of")
    val relationCancelled = StringKey("bill_receipt.relation_cancelled")
    val relationReversedByLink = StringKey("bill_receipt.relation_reversed_by_link")
    val relationBackTo = StringKey("bill_receipt.relation_back_to")
    val relationSectionTitle = StringKey("bill_receipt.relation_section_title")
    val relationRebookOf = StringKey("bill_receipt.relation_rebook_of")

    // {0} = the re-billed originals' numbers, comma-joined; the bill also holds new items.
    val relationRebookOfPartial = StringKey("bill_receipt.relation_rebook_of_partial")
    val relationRebookedByLink = StringKey("bill_receipt.relation_rebooked_by_link")
    val relationCancelledRebook = StringKey("bill_receipt.relation_cancelled_rebook")

    /** List badge of a Schulungsbeleg (AVTraining) — listed with every signed Beleg, never revenue. */
    val trainingBadge = StringKey("bill_receipt.training_badge")

    // Zahlartwechsel (Storno + Neubuchung) refusals.
    val rebookUnsupportedTitle = StringKey("bill_receipt.rebook_unsupported_title")
    val rebookUnsupportedMessage = StringKey("bill_receipt.rebook_unsupported_message")
    val rebookNoChangeTitle = StringKey("bill_receipt.rebook_no_change_title")
    val rebookNoChangeMessage = StringKey("bill_receipt.rebook_no_change_message")
    val rebookPromotionChangedTitle = StringKey("bill_receipt.rebook_promotion_changed_title")
    val rebookPromotionChangedMessage = StringKey("bill_receipt.rebook_promotion_changed_message")
    val rebookTipDecreaseTitle = StringKey("bill_receipt.rebook_tip_decrease_title")
    val rebookTipDecreaseMessage = StringKey("bill_receipt.rebook_tip_decrease_message")
    val invoiceIssuedTitle = StringKey("bill_receipt.invoice_issued_title")
    val invoiceIssuedMessage = StringKey("bill_receipt.invoice_issued_message")

    // A Storno or Zahlartwechsel refused because the bill was already voided or changed elsewhere.
    val billAlreadyVoidedTitle = StringKey("bill_receipt.bill_already_voided_title")
    val billAlreadyVoidedMessage = StringKey("bill_receipt.bill_already_voided_message")

    // Zahlartwechsel dialog: a tip below the original needs BILL_VOID.
    val rebookTipDecreaseHint = StringKey("bill_receipt.rebook_tip_decrease_hint")
}
