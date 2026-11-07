package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object DocumentManagementKeys {
    val uploadDialogTitle = StringKey("pdf_management.upload_dialog_title")
    val uploadButton = StringKey("pdf_management.upload_button")
    val selectFileButton = StringKey("pdf_management.select_file_button")
    val scanDocumentButton = StringKey("pdf_management.scan_document_button")
    val fileTypeLabel = StringKey("pdf_management.file_type_label")
    val descriptionLabel = StringKey("pdf_management.description_label")
    val ibanLabel = StringKey("pdf_management.iban_label")
    val uploadSuccessMessage = StringKey("pdf_management.upload_success_message")
    val requiredFieldError = StringKey("pdf_management.required_field_error")
    val vendorNameLabel = StringKey("pdf_management.vendor_name_label")
    val invoiceNumberLabel = StringKey("pdf_management.invoice_number_label")
    val invoiceDateLabel = StringKey("pdf_management.invoice_date_label")
    val dueDateLabel = StringKey("pdf_management.due_date_label")
    val netAmountLabel = StringKey("pdf_management.net_amount_label")
    val grossAmountLabel = StringKey("pdf_management.gross_amount_label")
    val taxAmountLabel = StringKey("pdf_management.tax_amount_label")
    val taxRateLabel = StringKey("pdf_management.tax_rate_label")
    val currencyLabel = StringKey("pdf_management.currency_label")
    val paymentReferenceLabel = StringKey("pdf_management.payment_reference_label")
    val supplierCityLabel = StringKey("pdf_management.supplier_city_label")
    val vatIdLabel = StringKey("pdf_management.vat_id_label")
    val accountNoLabel = StringKey("pdf_management.account_no_label")
    val bpAccountNoLabel = StringKey("pdf_management.bp_account_no_label")
    val deliveryDateLabel = StringKey("pdf_management.delivery_date_label")
    val bookingTextLabel = StringKey("pdf_management.booking_text_label")
    val invoiceNumberPlaceholder = StringKey("pdf_management.invoice_number_placeholder")
    val invoiceNumberHint = StringKey("pdf_management.invoice_number_hint")
    val bookingTextPlaceholder = StringKey("pdf_management.booking_text_placeholder")
    val bookingTextHint = StringKey("pdf_management.booking_text_hint")
    val paymentReferencePlaceholder = StringKey("pdf_management.payment_reference_placeholder")
    val paymentReferenceHint = StringKey("pdf_management.payment_reference_hint")
    val vatIdPlaceholder = StringKey("pdf_management.vat_id_placeholder")
    val ibanPlaceholder = StringKey("pdf_management.iban_placeholder")
    val bpAccountNoPlaceholder = StringKey("pdf_management.bp_account_no_placeholder")
    val accountNoPlaceholder = StringKey("pdf_management.account_no_placeholder")

    // The parsed bank-statement view. These live on because that view moved to adminApp
    // (StatementTransactionTable) when composeApp went till-only — the composeApp metadata
    // panel these sat beside is gone, the statement display it gained is not.
    val statementTransactionsTitle = StringKey("pdf_management.statement_transactions_title")
    val statementBookingDate = StringKey("pdf_management.statement_booking_date")
    val statementValueDate = StringKey("pdf_management.statement_value_date")
    val statementCounterparty = StringKey("pdf_management.statement_counterparty")
    val statementPurpose = StringKey("pdf_management.statement_purpose")
    val statementAmount = StringKey("pdf_management.statement_amount")
    val statementAlreadyImported = StringKey("pdf_management.statement_already_imported")
    val noPreviewAvailable = StringKey("pdf_management.no_preview_available")
}
