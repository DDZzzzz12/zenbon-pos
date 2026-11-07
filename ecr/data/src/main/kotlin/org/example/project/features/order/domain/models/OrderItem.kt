/**
 * Exposed DAO entity for OrderItem.
 *
 * Provides object-relational mapping for order items with proper
 * relationship management and type-safe database operations. Supports
 * detailed order tracking and customization.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.order.domain.models

import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.database.tables.CancellationTable
import org.example.project.features.order.database.tables.ItemExtrasTable
import org.example.project.features.order.database.tables.ItemTransferTable
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.product.domain.models.Product
import org.example.project.features.table.domain.models.TableSession
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Order item entity for detailed order management.
 *
 * Manages individual items within orders including pricing, status tracking,
 * and customization options. Links items to products and maintains order
 * item lifecycle information.
 */
class OrderItem(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<OrderItem>(OrderItemTable)

    /** Reference to parent order (null: a counter voucher sale attaches its item straight to the session) */
    var order by Orders optionalReferencedOn OrderItemTable.order

    var session by TableSession referencedOn OrderItemTable.sessionId

    /** Reference to a parent bill */
    var bill by Bill optionalReferencedOn OrderItemTable.billId

    var duplicatedFromBill by Bill optionalReferencedOn OrderItemTable.duplicatedFromBillId

    /** Reference to ordered product */
    var product by Product referencedOn OrderItemTable.productId

    /** Unit price at time of order with high precision */
    var unitPrice by OrderItemTable.unitPrice

    var finalPrice by OrderItemTable.finalPrice

    /** Current status of the order item */
    var itemStatus by OrderItemTable.itemStatus

    /** Reference to applicable VAT rate */
    var vat by Vat referencedOn OrderItemTable.vatId

    var discount by OrderItemTable.discount

    /** Whether custom pricing was applied */
    var customPrice by OrderItemTable.customPrice

    /** Whether this is a takeaway order */
    var takeAway by OrderItemTable.takeAway

    /** Whether this item was transferred from another order */
    var transferred by OrderItemTable.transferred

    /** Additional notes or customization requests */
    var sideNotes by OrderItemTable.sideNotes

    /** Captured options for this item (snapshotted at sale time). */
    val itemExtras by ItemExtras referrersOn ItemExtrasTable.orderItemId

    /** Cancellation record for this item (if cancelled) */
    val cancellation by Cancellation optionalBackReferencedOn CancellationTable.orderItemId

    val transfers by ItemTransfer optionalReferrersOn ItemTransferTable.orderItemId

    fun getEmployee(): Employee {
        if (order != null) return order!!.employee
        if (bill != null) return bill!!.employeeId
        if (duplicatedFromBill != null) return duplicatedFromBill!!.employeeId
        throw IllegalArgumentException("Cannot determine employee: order, bill, and duplicatedFromBill are all null")
    }
}
