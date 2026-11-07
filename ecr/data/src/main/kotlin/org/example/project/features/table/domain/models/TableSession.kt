/**
 * Table session entity class for tracking customer service sessions.
 *
 * Manages individual customer service sessions at restaurant tables,
 * tracking timing, employee assignment, and session completion status.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.table.domain.models

import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.customer.domain.models.Customer
import org.example.project.features.customer.domain.models.CustomerAddress
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.order.database.tables.OrdersTable
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.table.database.tables.TableSessionTable
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Entity representing a customer service session at a restaurant table.
 *
 * Tracks the duration of customer service, which employee is responsible,
 * and whether the session has been completed. Supports both dine-in and
 * delivery modes with customer and address association.
 */
class TableSession(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<TableSession>(TableSessionTable)

    /** The table where this session is taking place */
    var table by RTable referencedOn TableSessionTable.table

    /** The employee responsible for this session */
    var employeeId by Employee referencedOn TableSessionTable.employeeId

    /** When the session started */
    var startTime by TableSessionTable.startTime

    /** When the session ended (null if still active) */
    var endTime by TableSessionTable.endTime

    /** Whether the session has been completed */
    var completed by TableSessionTable.completed

    /** Z-Bon (daily closing report) reference */
    var zBon by ZBon optionalReferencedOn TableSessionTable.zBonId

    /** Optional notes for the session */
    var note by TableSessionTable.note

    /** Session type: DINING, QUICK_SALE, DELIVERY */
    var sessionType by TableSessionTable.sessionType

    /** See [TableSessionTable.tseTransactionId] — nothing populates this any more; retained on purpose. */
    var tseTransaction by TseTransaction optionalReferencedOn TableSessionTable.tseTransactionId

    /** Whether this session was created by a training-mode employee */
    var isTraining by TableSessionTable.isTraining

    // Delivery Support
    /** Whether this is a delivery order */
    var delivery by TableSessionTable.delivery

    /** Customer associated with this session (required for delivery, optional for dine-in loyalty) */
    var customer by Customer optionalReferencedOn TableSessionTable.customer

    /** Delivery address for delivery orders */
    var deliveryAddress by CustomerAddress optionalReferencedOn TableSessionTable.deliveryAddress

    /** Delivery phone number override (optional) */
    var deliveryPhoneNumber by TableSessionTable.deliveryPhoneNumber

    // Relationships
    /** Order items in this session */
    val orderItems by OrderItem referrersOn OrderItemTable.sessionId

    /** Orders placed during this session */
    val orders by Orders referrersOn OrdersTable.tableSessionId

    /** Bills created from this session */
    val bills by Bill referrersOn BillTable.tableSession

    /**
     * Mark this session as completed and close it.
     *
     * Sets the end time to current time and marks the session as completed.
     */
    fun end(message: String? = null) {
        endTime = currentTimeStamp()
        completed = true
        // Clear the table's reference whenever it still points at THIS session,
        // regardless of the table's current status. Prevents a completed session
        // from being left as a table's currentSession (the corrupt invariant).
        if (table.currentSession?.id?.value == this.id.value || table.tableStatus == TableStatus.OCCUPIED) {
            table.reset()
        }
        message?.let { note = it }
    }

    /**
     * Check if this is a delivery session.
     *
     * @return true if this session is for delivery
     */
    fun isDelivery(): Boolean = delivery

    /**
     * Check if this is a dine-in session.
     *
     * @return true if this session is for dine-in (not delivery)
     */
    fun isDineIn(): Boolean = !delivery

    /**
     * Get the delivery contact phone number.
     *
     * Returns the override phone if set, otherwise falls back to customer's phone.
     *
     * @return Delivery phone number or null
     */
    fun getDeliveryPhone(): String? = deliveryPhoneNumber ?: customer?.phone

    /**
     * Get the full delivery address as formatted string.
     *
     * @return Formatted delivery address or null if not a delivery session
     */
    fun getDeliveryAddressFormatted(): String? =
        if (delivery && deliveryAddress != null) {
            deliveryAddress?.formattedAddress
        } else {
            null
        }

    /**
     * Check if session has customer information.
     *
     * @return true if customer is associated with this session
     */
    fun hasCustomer(): Boolean = customer != null

    /**
     * Validate that delivery session has all required information.
     *
     * @return true if delivery session is valid (has customer and address)
     */
    fun isValidDeliverySession(): Boolean =
        if (delivery) {
            customer != null && deliveryAddress != null
        } else {
            true // Dine-in sessions don't need customer/address
        }
}
