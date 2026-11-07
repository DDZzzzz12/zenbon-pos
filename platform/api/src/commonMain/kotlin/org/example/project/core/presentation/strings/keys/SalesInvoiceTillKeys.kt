package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

/**
 * The till's *Rechnung erstellen* action for an already-settled bill.
 *
 * Deliberately small: the till collects buyer fields only. Line descriptions, quantities, VAT and
 * totals are snapshotted from the bill by the server and are never edited here, so no key exists for
 * them. Editing invoices is adminApp's surface.
 */
object SalesInvoiceTillKeys {
    val dialogTitle = StringKey("sales_invoice_till.dialog_title")
    val buyerSectionTitle = StringKey("sales_invoice_till.buyer_section_title")
    val amountsNotEditableHint = StringKey("sales_invoice_till.amounts_not_editable_hint")
    val buyerName = StringKey("sales_invoice_till.buyer_name")
    val buyerStreet = StringKey("sales_invoice_till.buyer_street")
    val buyerZip = StringKey("sales_invoice_till.buyer_zip")
    val buyerCity = StringKey("sales_invoice_till.buyer_city")
    val buyerCountry = StringKey("sales_invoice_till.buyer_country")
    val buyerVatId = StringKey("sales_invoice_till.buyer_vat_id")
    val buyerEmail = StringKey("sales_invoice_till.buyer_email")
    val requiredFieldsHint = StringKey("sales_invoice_till.required_fields_hint")
    val confirmButton = StringKey("sales_invoice_till.confirm_button")
    val showInvoiceButton = StringKey("sales_invoice_till.show_invoice_button")

    /** {0} = invoice number */
    val issuedMessage = StringKey("sales_invoice_till.issued_message")

    /** {0} = invoice number */
    val alreadyInvoicedHint = StringKey("sales_invoice_till.already_invoiced_hint")
    val notAvailableTraining = StringKey("sales_invoice_till.not_available_training")
    val notAvailableDeleted = StringKey("sales_invoice_till.not_available_deleted")

    /** Server refusal `BILL_NOT_A_STANDING_SALE`: invoice / Bewirtungsbeleg only for the original sale. */
    val notStandingSaleTitle = StringKey("sales_invoice_till.not_standing_sale_title")
    val notStandingSaleMessage = StringKey("sales_invoice_till.not_standing_sale_message")

    /** Server refusal `SALES_INVOICE_OWNER_TIP_NOT_SEPARABLE`: the owner tip cannot be taken out exactly. */
    val ownerTipNotSeparableTitle = StringKey("sales_invoice_till.owner_tip_not_separable_title")
    val ownerTipNotSeparableMessage = StringKey("sales_invoice_till.owner_tip_not_separable_message")

    /** Server refusal `SALES_INVOICE_BILL_NOT_RECONCILABLE`: the promotions do not reproduce the VAT details. */
    val billNotReconcilableTitle = StringKey("sales_invoice_till.bill_not_reconcilable_title")
    val billNotReconcilableMessage = StringKey("sales_invoice_till.bill_not_reconcilable_message")

    /** `SALES_INVOICE_TIP_ON_ACCOUNT`, and the till's hint when it hides *Rechnung erstellen* for it. */
    val tipOnAccountTitle = StringKey("sales_invoice_till.tip_on_account_title")
    val tipOnAccountMessage = StringKey("sales_invoice_till.tip_on_account_message")

    /**
     * Server refusal `SALES_INVOICE_BILL_ALREADY_INVOICED`: the bill already carries a live invoice
     * (issued from this dialog a moment ago, or on another device). Showed "Unerwarteter Fehler" (emu20 NOTE-q).
     */
    val alreadyInvoicedTitle = StringKey("sales_invoice_till.already_invoiced_title")
    val alreadyInvoicedMessage = StringKey("sales_invoice_till.already_invoiced_message")

    /** Shown under the disabled *Rechnung erstellen* when the session lacks `INVOICE_WRITE`. */
    val createInvoiceNeedsCapability = StringKey("sales_invoice_till.create_invoice_needs_capability")

    // --- 4c-2c: settle an open receivable at the till ---
    val settleReceivableButton = StringKey("sales_invoice_till.settle_receivable_button")
    val settleDialogTitle = StringKey("sales_invoice_till.settle_dialog_title")
    val settleAmountLabel = StringKey("sales_invoice_till.settle_amount_label")
    val settleTenderLabel = StringKey("sales_invoice_till.settle_tender_label")

    /** {0} = open amount */
    val settleOpenAmountLabel = StringKey("sales_invoice_till.settle_open_amount_label")
    val settleConfirmButton = StringKey("sales_invoice_till.settle_confirm_button")

    /** {0} = remaining open amount */
    val settleSucceeded = StringKey("sales_invoice_till.settle_succeeded")
}
