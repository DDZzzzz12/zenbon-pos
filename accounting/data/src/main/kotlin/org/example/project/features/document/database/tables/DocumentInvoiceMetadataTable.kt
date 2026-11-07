/**
 * Document Invoice Metadata Table Definition
 *
 * Stores invoice-specific metadata extracted via OCR or manual entry.
 * Applicable to INVOICE type documents only.
 *
 * Relationship: One-to-One with DocumentTable (required, unique)
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.database.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Exposed table definition for invoice-specific document metadata.
 *
 * Stores vendor information, invoice details, amounts with tax breakdown,
 * and payment references for invoices.
 */
object DocumentInvoiceMetadataTable : LongIdTable("document_invoice_metadata", "id") {
    /** Reference to the parent document (one-to-one relationship) */
    val documentId = reference("pdf_document_id", DocumentTable).uniqueIndex()

    /** Company or person issuing the invoice */
    val vendorName = varchar("vendor_name", 255).nullable()

    /** Invoice number (Rechnungsnummer) */
    val invoiceNumber = varchar("invoice_number", 100).nullable()

    /** Invoice date (Rechnungsdatum) */
    val invoiceDate = datetime("invoice_date").nullable()

    /** Payment due date (Fälligkeitsdatum) */
    val dueDate = datetime("due_date").nullable()

    /** Net amount before tax (Nettobetrag) */
    val netAmount = decimal("net_amount", 15, 2).nullable()

    /** Gross amount including tax (Bruttobetrag) */
    val grossAmount = decimal("gross_amount", 15, 2).nullable()

    /** Tax amount (MwSt/USt) */
    val taxAmount = decimal("tax_amount", 15, 2).nullable()

    /** Tax rate percentage (e.g. 19.00, 7.00) */
    val taxRate = decimal("tax_rate", 5, 2).nullable()

    /** Currency code (default EUR) */
    val currency = varchar("currency", 3).default("EUR")

    /** Payment IBAN */
    val iban = varchar("iban", 34).nullable()

    /** Payment reference / Verwendungszweck */
    val paymentReference = varchar("payment_reference", 255).nullable()

    // ===== DATEV AP (Eingangsrechnungen) FIELDS =====

    /** Supplier city for DATEV accountsPayableLedger */
    val supplierCity = varchar("supplier_city", 100).nullable()

    /** VAT identification number (e.g. DE123456789) */
    val vatId = varchar("vat_id", 50).nullable()

    /** Gegenkonto / G/L account number */
    val accountNo = varchar("account_no", 20).nullable()

    /** Kreditorenkonto / business partner account number (70000-999999999) */
    val bpAccountNo = varchar("bp_account_no", 20).nullable()

    /** Liefer-/Leistungsdatum (delivery/service date) */
    val deliveryDate = datetime("delivery_date").nullable()

    /** Buchungstext (booking text, max 60 chars) */
    val bookingText = varchar("booking_text", 60).nullable()

    /** JSON array of tax line items for split-tax invoices (nullable for single-tax backward compat) */
    val taxLineItemsJson = text("tax_line_items_json").nullable()

    /** JSON array of invoice line items (positions/articles) parsed from the invoice or entered manually */
    val lineItemsJson = text("line_items_json").nullable()

    // ===== PAYMENT INFORMATION =====

    /** JSON array of payment details — supports multiple payment methods per invoice */
    val paymentDetailsJson = text("payment_details_json").nullable()

    /** Discount terms (Skonto) as JSON — percentage, deadline, amount */
    val discountTermsJson = text("discount_terms_json").nullable()

    // ===== INVOICE IDENTIFICATION =====

    /** Invoice type: Rechnung, Gutschrift § 14 UStG, Gutschrift/Rechnungskorrektur, etc. */
    val invoiceType = varchar("invoice_type", 30).nullable()

    /** Original invoice number referred to by a Gutschrift (credit note) */
    val draweeNo = varchar("drawee_no", 100).nullable()

    /** Order reference number if present on invoice */
    val orderId = varchar("order_id", 100).nullable()

    // ===== SUPPLIER ADDRESS =====

    /** Supplier street address */
    val supplierStreet = varchar("supplier_street", 255).nullable()

    /** Supplier postal code */
    val supplierZip = varchar("supplier_zip", 20).nullable()

    /** Supplier country code (2-letter ISO) */
    val supplierCountry = varchar("supplier_country", 10).nullable()

    /** Supplier phone number */
    val supplierPhone = varchar("supplier_phone", 50).nullable()

    /** Supplier email address */
    val supplierEmail = varchar("supplier_email", 255).nullable()

    /** Destination country for OSS compliance */
    val shipToCountry = varchar("ship_to_country", 10).nullable()

    /** Origin country for OSS compliance */
    val shipFromCountry = varchar("ship_from_country", 10).nullable()

    // ===== PAYMENT CONDITIONS =====

    /** Payment terms text (e.g., "30 days net") */
    val paymentConditionsText = varchar("payment_conditions_text", 255).nullable()

    /** Date when invoice was paid (null if unpaid) */
    val paidAt = datetime("paid_at").nullable()

    /** Whether payment is blocked due to dunning */
    val paymentDunningBlock = bool("payment_dunning_block").default(false)

    // ===== ACCOUNTING CONTROLS =====

    /** Cost center 1 (Kost1) */
    val costCategoryId = varchar("cost_category_id", 20).nullable()

    /** Cost center 2 (Kost2) */
    val costCategoryId2 = varchar("cost_category_id2", 20).nullable()

    /** Cost center amount */
    val costAmount = decimal("cost_amount", 15, 2).nullable()

    /** EUR exchange rate for foreign currency invoices (6 decimal places) */
    val exchangeRate = decimal("exchange_rate", 18, 6).nullable()

    /** EU cross-border tax rate */
    val euTaxRate = decimal("eu_tax_rate", 5, 2).nullable()

    /** DATEV B/U key (Belegart-Ursprung) */
    val buCode = varchar("bu_code", 10).nullable()

    /** Type of receivable (Forderungstyp) */
    val typeOfReceivable = varchar("type_of_receivable", 50).nullable()

    // ===== DELIVERY PERIOD =====

    /** Service/delivery period start date */
    val deliveryDateStart = datetime("delivery_date_start").nullable()

    /** Service/delivery period end date */
    val deliveryDateEnd = datetime("delivery_date_end").nullable()

    // ===== CATCH-ALL =====

    /** JSON array of additional info entries with type and content fields */
    val additionalInfoJson = text("additional_info_json").nullable()
}
