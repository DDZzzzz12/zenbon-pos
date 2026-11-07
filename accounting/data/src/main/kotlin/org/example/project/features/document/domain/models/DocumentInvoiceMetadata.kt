/**
 * Document Invoice Metadata Entity - Exposed DAO
 *
 * Domain model representing invoice-specific metadata associated with a document.
 * Maps to the document_invoice_metadata table.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.domain.models

import org.example.project.features.document.database.tables.DocumentInvoiceMetadataTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Exposed DAO entity for invoice-specific document metadata.
 *
 * Provides access to vendor information, invoice details, amounts
 * with tax breakdown, and payment references.
 */
class DocumentInvoiceMetadata(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<DocumentInvoiceMetadata>(DocumentInvoiceMetadataTable)

    /** Reference to the parent document */
    var document by Document referencedOn DocumentInvoiceMetadataTable.documentId

    /** Company or person issuing the invoice */
    var vendorName by DocumentInvoiceMetadataTable.vendorName

    /** Invoice number (Rechnungsnummer) */
    var invoiceNumber by DocumentInvoiceMetadataTable.invoiceNumber

    /** Invoice date (Rechnungsdatum) */
    var invoiceDate by DocumentInvoiceMetadataTable.invoiceDate

    /** Payment due date (Fälligkeitsdatum) */
    var dueDate by DocumentInvoiceMetadataTable.dueDate

    /** Net amount before tax */
    var netAmount by DocumentInvoiceMetadataTable.netAmount

    /** Gross amount including tax */
    var grossAmount by DocumentInvoiceMetadataTable.grossAmount

    /** Tax amount */
    var taxAmount by DocumentInvoiceMetadataTable.taxAmount

    /** Tax rate percentage */
    var taxRate by DocumentInvoiceMetadataTable.taxRate

    /** Currency code */
    var currency by DocumentInvoiceMetadataTable.currency

    /** Payment IBAN */
    var iban by DocumentInvoiceMetadataTable.iban

    /** Payment reference / Verwendungszweck */
    var paymentReference by DocumentInvoiceMetadataTable.paymentReference

    // ===== DATEV AP (Eingangsrechnungen) FIELDS =====

    /** Supplier city for DATEV accountsPayableLedger */
    var supplierCity by DocumentInvoiceMetadataTable.supplierCity

    /** VAT identification number (e.g. DE123456789) */
    var vatId by DocumentInvoiceMetadataTable.vatId

    /** Gegenkonto / G/L account number */
    var accountNo by DocumentInvoiceMetadataTable.accountNo

    /** Kreditorenkonto / business partner account number */
    var bpAccountNo by DocumentInvoiceMetadataTable.bpAccountNo

    /** Liefer-/Leistungsdatum (delivery/service date) */
    var deliveryDate by DocumentInvoiceMetadataTable.deliveryDate

    /** Buchungstext (booking text, max 60 chars) */
    var bookingText by DocumentInvoiceMetadataTable.bookingText

    /** JSON array of tax line items for split-tax invoices */
    var taxLineItemsJson by DocumentInvoiceMetadataTable.taxLineItemsJson

    /** JSON array of invoice line items (positions/articles) parsed from the invoice or entered manually */
    var lineItemsJson by DocumentInvoiceMetadataTable.lineItemsJson

    /** JSON array of payment details */
    var paymentDetailsJson by DocumentInvoiceMetadataTable.paymentDetailsJson

    /** JSON discount terms (Skonto) */
    var discountTermsJson by DocumentInvoiceMetadataTable.discountTermsJson

    // ===== INVOICE IDENTIFICATION =====

    /** Invoice type: Rechnung, Gutschrift § 14 UStG, etc. */
    var invoiceType by DocumentInvoiceMetadataTable.invoiceType

    /** Original invoice number referred to by a Gutschrift (credit note) */
    var draweeNo by DocumentInvoiceMetadataTable.draweeNo

    /** Order reference number if present on invoice */
    var orderId by DocumentInvoiceMetadataTable.orderId

    // ===== SUPPLIER ADDRESS =====

    /** Supplier street address */
    var supplierStreet by DocumentInvoiceMetadataTable.supplierStreet

    /** Supplier postal code */
    var supplierZip by DocumentInvoiceMetadataTable.supplierZip

    /** Supplier country code (2-letter ISO) */
    var supplierCountry by DocumentInvoiceMetadataTable.supplierCountry

    /** Supplier phone number */
    var supplierPhone by DocumentInvoiceMetadataTable.supplierPhone

    /** Supplier email address */
    var supplierEmail by DocumentInvoiceMetadataTable.supplierEmail

    /** Destination country for OSS compliance */
    var shipToCountry by DocumentInvoiceMetadataTable.shipToCountry

    /** Origin country for OSS compliance */
    var shipFromCountry by DocumentInvoiceMetadataTable.shipFromCountry

    // ===== PAYMENT CONDITIONS =====

    /** Payment terms text */
    var paymentConditionsText by DocumentInvoiceMetadataTable.paymentConditionsText

    /** Date when invoice was paid */
    var paidAt by DocumentInvoiceMetadataTable.paidAt

    /** Whether payment is blocked due to dunning */
    var paymentDunningBlock by DocumentInvoiceMetadataTable.paymentDunningBlock

    // ===== ACCOUNTING CONTROLS =====

    /** Cost center 1 (Kost1) */
    var costCategoryId by DocumentInvoiceMetadataTable.costCategoryId

    /** Cost center 2 (Kost2) */
    var costCategoryId2 by DocumentInvoiceMetadataTable.costCategoryId2

    /** Cost center amount */
    var costAmount by DocumentInvoiceMetadataTable.costAmount

    /** EUR exchange rate for foreign currency invoices */
    var exchangeRate by DocumentInvoiceMetadataTable.exchangeRate

    /** EU cross-border tax rate */
    var euTaxRate by DocumentInvoiceMetadataTable.euTaxRate

    /** DATEV B/U key */
    var buCode by DocumentInvoiceMetadataTable.buCode

    /** Type of receivable */
    var typeOfReceivable by DocumentInvoiceMetadataTable.typeOfReceivable

    // ===== DELIVERY PERIOD =====

    /** Service/delivery period start date */
    var deliveryDateStart by DocumentInvoiceMetadataTable.deliveryDateStart

    /** Service/delivery period end date */
    var deliveryDateEnd by DocumentInvoiceMetadataTable.deliveryDateEnd

    // ===== CATCH-ALL =====

    /** JSON array of additional info entries */
    var additionalInfoJson by DocumentInvoiceMetadataTable.additionalInfoJson

    /**
     * Checks if this metadata has any meaningful data set.
     *
     * @return true if at least one field beyond currency is populated
     */
    fun hasData(): Boolean =
        vendorName != null ||
            invoiceNumber != null ||
            invoiceDate != null ||
            dueDate != null ||
            netAmount != null ||
            grossAmount != null ||
            taxAmount != null ||
            taxRate != null ||
            iban != null ||
            paymentReference != null

    /**
     * Formats the gross amount to a display string with 2 decimal places.
     *
     * @return Formatted amount string or null if not set
     */
    fun formatAmount(): String? = grossAmount?.setScale(2)?.toPlainString()

    override fun toString(): String = "DocumentInvoiceMetadata(id=$id, vendor=$vendorName, invoiceNr=$invoiceNumber, grossAmount=$grossAmount)"
}
