/**
 * Exposed table definition for order items.
 *
 * Defines the database schema structure for individual items within orders.
 * Supports detailed order tracking, pricing, and customization with proper
 * relationships to products, tables, and VAT information.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.order.database.tables

import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.payment.database.tables.VatTable
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.table.database.tables.TableSessionTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import java.math.BigDecimal

/**
 * Exposed table definition for t_order_item table.
 *
 * Manages individual items within orders including pricing, status tracking,
 * and customization options. Links items to products and maintains order
 * item lifecycle information.
 */
object OrderItemTable : LongIdTable("t_order_item", "id") {
    /** Reference to parent order (nullable: a counter voucher sale attaches its item straight to the session) */
    val order = reference("orders_id", OrdersTable).nullable()

    val sessionId = reference("table_session_id", TableSessionTable)

    /** Reference to bill (optional, for billed items) */
    val billId = reference("bill_id", BillTable).nullable()

    /** Reference to ordered product */
    val productId = reference("product_id", ProductTable)

    /** Unit price at time of order with high precision */
    val unitPrice = decimal("unit_price", precision = 19, scale = 4)

    /** Total price of this item with high precision */
    val finalPrice = decimal("final_price", precision = 19, scale = 4).default(BigDecimal.ZERO)

    /** Current status of the order item */
    val itemStatus = enumerationByName("item_status", 50, ItemStatus::class)

    /** Reference to applicable VAT rate */
    val vatId = reference("vat_id", VatTable)

    /** discount percentage applied to this item
     *  (e.g., 0.10 for 10%)
     *  default is 100% (1.00)
     * */
    val discount = decimal("discount", precision = 4, scale = 2).default(BigDecimal.valueOf(1.00))

    /** Whether custom pricing was applied */
    val customPrice = bool("custom_price").default(false)

    /** Whether this is a takeaway order */
    val takeAway = bool("take_away")

    /** Whether this item was transferred from or to another order */
    val transferred = bool("transferred").default(false)

    /** Additional notes or customization requests */
    val sideNotes = varchar("side_notes", 10000).nullable()

    val duplicatedFromBillId = reference("duplicated_from_bill_id", BillTable).nullable()

    val timestamp = timestamp("timestamp").defaultExpression(CurrentTimestamp)

    init {
        // Query hot paths — kept in sync with Flyway V84 (matching index names so fresh
        // installs (Exposed-created schema) and existing installs (migration) converge).
        index("idx_order_item_session_status", false, sessionId, itemStatus)
        index("idx_order_item_orders_id", false, order)
        index("idx_order_item_product_id", false, productId)
        index("idx_order_item_bill_id", false, billId)
    }
}
