package org.example.project.features.document.database.tables

import org.example.project.features.contract.database.tables.ContractTable
import org.example.project.features.supplier.database.tables.SupplierTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * A supplier invoice. The **root** — it references the file it came from rather than hanging off it.
 *
 * [sourceDocumentId] is nullable on purpose: XRechnung is frequently delivered as bare CII/UBL XML
 * with no PDF, and the old model's NOT NULL FK made such an invoice impossible to represent.
 *
 * Everything here is supplier-asserted. Our bookkeeping decisions live in [InvoiceBookingTable].
 */
object InvoiceTable : LongIdTable("invoice", "id") {
    val sourceDocumentId = optReference("source_document_id", DocumentTable, onDelete = ReferenceOption.SET_NULL)

    /** `zugferd` | `factur-x` | `xrechnung` | `ubl`; null when not from a structured payload. */
    val eInvoiceFormat = varchar("e_invoice_format", 20).nullable()

    /**
     * `EINVOICE_XML` | `OCR_LLM` | `MANUAL` — drives how the UI treats a correction.
     * `OCR_LLM` is retained for historical rows only and is never written any more.
     */
    val extractionSource = varchar("extraction_source", 20).default("MANUAL")

    val invoiceNumber = varchar("invoice_number", 100).nullable()
    val issueDate = datetime("issue_date").nullable()
    val dueDate = datetime("due_date").nullable()
    val deliveryDate = datetime("delivery_date").nullable()
    val currency = varchar("currency", 3).default("EUR")

    /**
     * BT-3 Invoice type code, UNTDID 1001 — `380` Rechnung, `381` Gutschrift, `384` Korrektur.
     *
     * Defaults to `380`: an invoice is an ordinary invoice unless the sender says otherwise. A
     * parsed document keeps whatever code it carried, even one outside those three — the short
     * capture select is a form concern, not a storage constraint.
     */
    val invoiceTypeCode = varchar("invoice_type_code", 10).default("380")

    /** BT-25 Preceding invoice reference — the invoice a Gutschrift or Korrektur refers to. */
    val precedingInvoiceReference = varchar("preceding_invoice_reference", 100).nullable()

    /** BT-10 Buyer reference — the Leitweg-ID, mandatory in XRechnung. */
    val buyerReference = varchar("buyer_reference", 100).nullable()

    val sellerName = varchar("seller_name", 255).nullable()
    val sellerVatId = varchar("seller_vat_id", 50).nullable()
    val sellerTaxNumber = varchar("seller_tax_number", 50).nullable()
    val sellerStreet = varchar("seller_street", 255).nullable()
    val sellerZip = varchar("seller_zip", 20).nullable()
    val sellerCity = varchar("seller_city", 100).nullable()
    val sellerCountry = varchar("seller_country", 10).nullable()
    val sellerPhone = varchar("seller_phone", 50).nullable()
    val sellerEmail = varchar("seller_email", 255).nullable()

    /**
     * The resolved creditor. Nullable, and **alongside** the `seller_*` snapshot rather than
     * replacing it: those fields are what the supplier asserted on this document and must not
     * change when master data is edited.
     */
    val supplierId =
        optReference("supplier_id", SupplierTable, onDelete = ReferenceOption.SET_NULL)

    /** The contract this invoice belongs to, when it arrived under one. */
    val contractId = optReference("contract_id", ContractTable, onDelete = ReferenceOption.SET_NULL)

    val paymentIban = varchar("payment_iban", 34).nullable()
    val paymentReference = varchar("payment_reference", 255).nullable()
    val paymentTermsText = varchar("payment_terms_text", 255).nullable()

    val lineNetTotal = decimal("line_net_total", 19, 4).nullable()
    val taxExclusiveTotal = decimal("tax_exclusive_total", 19, 4).nullable()
    val taxTotal = decimal("tax_total", 19, 4).nullable()
    val taxInclusiveTotal = decimal("tax_inclusive_total", 19, 4).nullable()
    val amountDue = decimal("amount_due", 19, 4).nullable()

    /**
     * What is still owed on this invoice: `coalesce(amount_due, tax_inclusive_total) − Σ allocations`.
     *
     * **Derived but persisted.** Computing it as a subquery per row makes the open-item list
     * unindexable, so `AllocationWriter` writes it in the same transaction as every allocation and a
     * test recomputes it from scratch to prove it has not drifted.
     *
     * Null means "never had an allocation and no total to derive from" — distinct from zero, which
     * means settled.
     */
    val openAmount = decimal("open_amount", 19, 4).nullable()

    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at").nullable()

    /**
     * When a human confirmed this invoice's content, or null if nobody has yet.
     *
     * Drives the Rechnungen buckets: null is *Zu prüfen*, set is *Fertig*. It lives here rather than
     * on `document.processing_status` because an invoice may have **no document** — a bare XRechnung
     * XML, or one entered by hand — and the document's status cannot speak for it.
     *
     * A timestamp rather than a boolean: confirmation is an act that happened at a time, and the
     * time is what an auditor asks for.
     */
    val reviewedAt = datetime("reviewed_at").nullable()

    init {
        index("idx_invoice_invoice_number", false, invoiceNumber)
        index("idx_invoice_issue_date", false, issueDate)
        index("idx_invoice_seller_name", false, sellerName)
        index("idx_invoice_source_document_id", false, sourceDocumentId)
        index("idx_invoice_supplier_id", false, supplierId)
        index("idx_invoice_contract_id", false, contractId)
        index("idx_invoice_reviewed_at", false, reviewedAt)
        index("idx_invoice_open_amount", false, openAmount)
    }
}
