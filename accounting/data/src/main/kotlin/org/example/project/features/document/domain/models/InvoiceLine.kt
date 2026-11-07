/**
 * Invoice Line Entity - Exposed DAO
 *
 * Domain model representing a single EN 16931 BG-25 invoice line.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.domain.models

import org.example.project.features.document.database.tables.InvoiceLineTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Exposed DAO entity for an invoice line item (EN 16931 BG-25).
 */
class InvoiceLine(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<InvoiceLine>(InvoiceLineTable)

    /** Parent invoice. */
    var invoice by Invoice referencedOn InvoiceLineTable.invoiceId

    var lineId by InvoiceLineTable.lineId
    var itemName by InvoiceLineTable.itemName
    var itemDescription by InvoiceLineTable.itemDescription

    var quantity by InvoiceLineTable.quantity

    /** UN/ECE Rec 20 — `C62` piece, `KGM`, `LTR`, `HUR`. */
    var unitCode by InvoiceLineTable.unitCode

    /** BT-146. Four decimals, deliberately — see the spec's money note. */
    var netUnitPrice by InvoiceLineTable.netUnitPrice
    var lineNetAmount by InvoiceLineTable.lineNetAmount

    var vatCategoryCode by InvoiceLineTable.vatCategoryCode
    var vatRate by InvoiceLineTable.vatRate

    var sellerItemId by InvoiceLineTable.sellerItemId
    var buyerItemId by InvoiceLineTable.buyerItemId
    var itemStandardId by InvoiceLineTable.itemStandardId

    override fun toString(): String = "InvoiceLine(id=$id, lineId=$lineId, item=$itemName, netAmount=$lineNetAmount)"
}
