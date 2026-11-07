/**
 * Invoice VAT Breakdown Entity - Exposed DAO
 *
 * Domain model representing a single EN 16931 BG-23 VAT breakdown row
 * (one row per category/rate combination).
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.domain.models

import org.example.project.features.document.database.tables.InvoiceVatBreakdownTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Exposed DAO entity for an invoice VAT breakdown row (EN 16931 BG-23).
 *
 * Replaces `document_invoice_metadata.tax_line_items_json` — the DATEV AP export emits one
 * ledger entry per row here.
 */
class InvoiceVatBreakdown(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<InvoiceVatBreakdown>(InvoiceVatBreakdownTable)

    /** Parent invoice. */
    var invoice by Invoice referencedOn InvoiceVatBreakdownTable.invoiceId

    /** UNCL5305: S, Z, E, AE, K, G, O. */
    var vatCategoryCode by InvoiceVatBreakdownTable.vatCategoryCode
    var vatRate by InvoiceVatBreakdownTable.vatRate
    var taxableAmount by InvoiceVatBreakdownTable.taxableAmount
    var vatAmount by InvoiceVatBreakdownTable.vatAmount
    var exemptionReasonCode by InvoiceVatBreakdownTable.exemptionReasonCode
    var exemptionReasonText by InvoiceVatBreakdownTable.exemptionReasonText

    /**
     * Per-line booking text. Not an EN 16931 term — BG-23 has no description. It exists because
     * the DATEV AP export emits one ledger entry per breakdown row and sets that entry's
     * booking text from this field, falling back to the invoice-level text only when blank.
     */
    var description by InvoiceVatBreakdownTable.description

    override fun toString(): String = "InvoiceVatBreakdown(id=$id, category=$vatCategoryCode, rate=$vatRate, vatAmount=$vatAmount)"
}
