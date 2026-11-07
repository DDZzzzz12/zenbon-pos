package org.example.project.features.salesinvoice.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * EN 16931 BG-23 VAT breakdown — one row per (category, rate).
 *
 * For a POS-derived invoice these rows are **copied from the bill's fiscal split, never
 * recomputed**. Recomputing from unit prices can land a cent away from the printed receipt, and a
 * receipt and an invoice for the same sale disagreeing by a cent is what a Betriebsprüfer picks up.
 */
object SalesInvoiceVatBreakdownTable : LongIdTable("sales_invoice_vat_breakdown", "id") {
    val salesInvoiceId =
        reference("sales_invoice_id", SalesInvoiceTable, onDelete = ReferenceOption.CASCADE)

    /** UNCL5305: S, Z, E, AE, K, G, O. */
    val vatCategoryCode = varchar("vat_category_code", 5)
    val vatRate = decimal("vat_rate", 5, 2)
    val taxableAmount = decimal("taxable_amount", 19, 4)
    val vatAmount = decimal("vat_amount", 19, 4)
    val exemptionReasonText = varchar("exemption_reason_text", 500).nullable()

    init {
        index("idx_sales_invoice_vat_invoice_id", false, salesInvoiceId)
    }
}
