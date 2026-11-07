package org.example.project.features.salesinvoice.database.tables

import org.example.project.features.order.database.tables.OrderItemTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import java.math.BigDecimal

/**
 * One line of an outgoing invoice — EN 16931 BG-25.
 *
 * The item's name and price are **snapshotted, not referenced**: renaming a Schnitzel or repricing
 * it must not rewrite last year's invoice. [sourceOrderItemId] records where the line came from for
 * audit, and is deliberately not the source of any displayed value.
 */
object SalesInvoiceLineTable : LongIdTable("sales_invoice_line", "id") {
    val salesInvoiceId =
        reference("sales_invoice_id", SalesInvoiceTable, onDelete = ReferenceOption.CASCADE)

    val lineNo = integer("line_no")
    val itemName = varchar("item_name", 500)
    val itemDescription = text("item_description").nullable()

    val quantity = decimal("quantity", 19, 4)

    /** UN/ECE Rec 20 — `C62` piece, `KGM`, `LTR`, `HUR`. */
    val unitCode = varchar("unit_code", 10).default("C62")

    val netUnitPrice = decimal("net_unit_price", 19, 4)
    val lineNetAmount = decimal("line_net_amount", 19, 4)
    val vatRate = decimal("vat_rate", 5, 2)
    val vatCategoryCode = varchar("vat_category_code", 5).default("S")
    val vatAmount = decimal("vat_amount", 19, 4)
    val lineGrossAmount = decimal("line_gross_amount", 19, 4)

    /**
     * The gross unit price the operator **typed**, stored verbatim. The editor reads this back on
     * reopen instead of re-deriving `line_gross_amount / quantity`, which cannot round-trip (qty 3
     * at 3.3333 stores gross 10.00 and re-derives 3.33 — a cent lost on every open-and-save).
     *
     * The Kotlin-side default mirrors the migration's `DEFAULT 0` and exists because Exposed DAO
     * batches inserts: a row that omits the column would otherwise fail the whole batch. Production
     * writers always set the real value; the default only covers callers that predate the column.
     */
    val grossUnitPrice = decimal("gross_unit_price", 19, 4).default(BigDecimal.ZERO)

    /** Audit trail back to the till line this was rendered from. */
    val sourceOrderItemId =
        optReference("source_order_item_id", OrderItemTable, onDelete = ReferenceOption.SET_NULL)

    init {
        index("idx_sales_invoice_line_invoice_id", false, salesInvoiceId)
        uniqueIndex("sales_invoice_line_no_unique", salesInvoiceId, lineNo)
    }
}
