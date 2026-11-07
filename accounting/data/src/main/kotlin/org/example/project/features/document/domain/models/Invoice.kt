/**
 * Invoice Entity - Exposed DAO
 *
 * Domain model representing a supplier invoice. The aggregate root — it references the
 * document it came from rather than hanging off it, so a bare XRechnung XML with no PDF
 * can still be represented.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.domain.models

import org.example.project.features.contract.domain.models.Contract
import org.example.project.features.document.database.tables.InvoiceAllowanceChargeTable
import org.example.project.features.document.database.tables.InvoiceBookingTable
import org.example.project.features.document.database.tables.InvoiceLineTable
import org.example.project.features.document.database.tables.InvoiceTable
import org.example.project.features.document.database.tables.InvoiceVatBreakdownTable
import org.example.project.features.supplier.domain.models.Supplier
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Exposed DAO entity for supplier invoices.
 *
 * All supplier-asserted content lives here and on the child entities. Our own bookkeeping
 * decisions live on the separate 1:1 [InvoiceBooking], so an edit form owning booking fields
 * structurally cannot null parsed supplier data.
 */
class Invoice(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<Invoice>(InvoiceTable)

    /** Source document this invoice was extracted from. Nullable — bare XRechnung XML has no PDF. */
    var sourceDocument by Document optionalReferencedOn InvoiceTable.sourceDocumentId

    /** `zugferd` | `factur-x` | `xrechnung` | `ubl`; null when not from a structured payload. */
    var eInvoiceFormat by InvoiceTable.eInvoiceFormat

    /**
     * `EINVOICE_XML` | `OCR_LLM` | `MANUAL` — drives how the UI treats a correction.
     * `OCR_LLM` is retained for historical rows only and is never written any more.
     */
    var extractionSource by InvoiceTable.extractionSource

    var invoiceNumber by InvoiceTable.invoiceNumber
    var issueDate by InvoiceTable.issueDate
    var dueDate by InvoiceTable.dueDate
    var deliveryDate by InvoiceTable.deliveryDate
    var currency by InvoiceTable.currency

    /** BT-3 Invoice type code, UNTDID 1001 — `380` Rechnung, `381` Gutschrift, `384` Korrektur. */
    var invoiceTypeCode by InvoiceTable.invoiceTypeCode

    /** BT-25 Preceding invoice reference — the invoice a Gutschrift or Korrektur refers to. */
    var precedingInvoiceReference by InvoiceTable.precedingInvoiceReference

    /** BT-10 Buyer reference — the Leitweg-ID, mandatory in XRechnung. */
    var buyerReference by InvoiceTable.buyerReference

    var sellerName by InvoiceTable.sellerName
    var sellerVatId by InvoiceTable.sellerVatId
    var sellerTaxNumber by InvoiceTable.sellerTaxNumber
    var sellerStreet by InvoiceTable.sellerStreet
    var sellerZip by InvoiceTable.sellerZip
    var sellerCity by InvoiceTable.sellerCity
    var sellerCountry by InvoiceTable.sellerCountry
    var sellerPhone by InvoiceTable.sellerPhone
    var sellerEmail by InvoiceTable.sellerEmail

    /** Resolved creditor; null until [SupplierResolver] has run or a human has linked one. */
    var supplier by Supplier optionalReferencedOn InvoiceTable.supplierId

    /** The contract this invoice arrived under, when it arrived under one. */
    var contract by Contract optionalReferencedOn InvoiceTable.contractId

    var paymentIban by InvoiceTable.paymentIban
    var paymentReference by InvoiceTable.paymentReference
    var paymentTermsText by InvoiceTable.paymentTermsText

    var lineNetTotal by InvoiceTable.lineNetTotal
    var taxExclusiveTotal by InvoiceTable.taxExclusiveTotal
    var taxTotal by InvoiceTable.taxTotal
    var taxInclusiveTotal by InvoiceTable.taxInclusiveTotal
    var amountDue by InvoiceTable.amountDue

    /** Residual still owed. Derived but persisted — see [InvoiceTable.openAmount]. */
    var openAmount by InvoiceTable.openAmount

    var createdAt by InvoiceTable.createdAt
    var updatedAt by InvoiceTable.updatedAt

    /**
     * When a human confirmed this invoice's content; null until someone has. Null is *Zu prüfen*,
     * set is *Fertig*. See [InvoiceTable.reviewedAt] for why it lives on the invoice and not on the
     * document's `processing_status`.
     */
    var reviewedAt by InvoiceTable.reviewedAt

    /** EN 16931 BG-25 invoice lines. */
    val lines by InvoiceLine referrersOn InvoiceLineTable.invoiceId

    /** EN 16931 BG-23 VAT breakdown rows. */
    val vatBreakdown by InvoiceVatBreakdown referrersOn InvoiceVatBreakdownTable.invoiceId

    /** EN 16931 BG-20/BG-21 document-level allowances and charges. */
    val allowancesCharges by InvoiceAllowanceCharge referrersOn InvoiceAllowanceChargeTable.invoiceId

    /** Our bookkeeping decisions for this invoice. 1:1, optional until booked. */
    val booking by InvoiceBooking optionalBackReferencedOn InvoiceBookingTable.invoiceId

    override fun toString(): String = "Invoice(id=$id, number=$invoiceNumber, seller=$sellerName)"
}
