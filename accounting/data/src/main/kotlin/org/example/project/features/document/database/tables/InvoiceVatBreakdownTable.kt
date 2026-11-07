package org.example.project.features.document.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * EN 16931 BG-23 VAT breakdown — one row per (category, rate).
 *
 * This replaces `document_invoice_metadata.tax_line_items_json`. The DATEV AP export emits one
 * ledger entry per row here, which was previously a JSON parse.
 */
object InvoiceVatBreakdownTable : LongIdTable("invoice_vat_breakdown", "id") {
    val invoiceId = reference("invoice_id", InvoiceTable, onDelete = ReferenceOption.CASCADE)

    /** UNCL5305: S, Z, E, AE, K, G, O. */
    val vatCategoryCode = varchar("vat_category_code", 5)
    val vatRate = decimal("vat_rate", 5, 2)
    val taxableAmount = decimal("taxable_amount", 19, 4)
    val vatAmount = decimal("vat_amount", 19, 4)
    val exemptionReasonCode = varchar("exemption_reason_code", 50).nullable()
    val exemptionReasonText = varchar("exemption_reason_text", 500).nullable()

    /**
     * Per-line booking text. **Not an EN 16931 term** — BG-23 has no description. It exists because
     * the DATEV AP export emits one ledger entry per breakdown row and sets that entry's
     * `bookingText` from the old `TaxLineItemDto.description`, falling back to the invoice-level
     * text only when blank. Dropping this column would silently change fiscal output.
     */
    val description = varchar("description", 500).nullable()

    init {
        index("idx_invoice_vat_breakdown_invoice_id", false, invoiceId)
    }
}
