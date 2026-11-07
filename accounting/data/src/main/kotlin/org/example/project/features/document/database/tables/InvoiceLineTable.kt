package org.example.project.features.document.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/** EN 16931 BG-25 invoice line. */
object InvoiceLineTable : LongIdTable("invoice_line", "id") {
    val invoiceId = reference("invoice_id", InvoiceTable, onDelete = ReferenceOption.CASCADE)

    val lineId = varchar("line_id", 50)
    val itemName = varchar("item_name", 500)
    val itemDescription = text("item_description").nullable()

    val quantity = decimal("quantity", 19, 4)

    /** UN/ECE Rec 20 — `C62` piece, `KGM`, `LTR`, `HUR`. */
    val unitCode = varchar("unit_code", 10)

    /** BT-146. Four decimals, deliberately — see the spec's money note. */
    val netUnitPrice = decimal("net_unit_price", 19, 4)
    val lineNetAmount = decimal("line_net_amount", 19, 4)

    val vatCategoryCode = varchar("vat_category_code", 5)
    val vatRate = decimal("vat_rate", 5, 2)

    val sellerItemId = varchar("seller_item_id", 100).nullable()
    val buyerItemId = varchar("buyer_item_id", 100).nullable()
    val itemStandardId = varchar("item_standard_id", 100).nullable()

    init {
        index("idx_invoice_line_invoice_id", false, invoiceId)
        uniqueIndex("idx_invoice_line_invoice_id_line_id", invoiceId, lineId)
    }
}
