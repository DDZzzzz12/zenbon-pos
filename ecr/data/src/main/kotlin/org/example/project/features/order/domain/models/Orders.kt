/**
 * Exposed DAO entity for Orders.
 *
 * Provides object-relational mapping for customer orders with proper
 * relationship management and type-safe database operations. Central
 * entity for order management and tracking.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.order.domain.models

import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.order.database.tables.OrdersTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.domain.models.TseTransaction
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Orders entity for customer order management.
 *
 * Manages customer orders including order tracking, table assignment,
 * and order totals. Links orders to employees and tables for comprehensive
 * order management.
 */
class Orders(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<Orders>(OrdersTable)

    /** Unique order identifier for business operations */
    var orderNumber by OrdersTable.orderNumber

    /** Reference to employee who created the order */
    var employee by Employee referencedOn OrdersTable.employeeId

    /** Reference to table where order was placed */
    var tableSession by TableSession referencedOn OrdersTable.tableSessionId

    /** Timestamp when order was created */
    var orderTime by OrdersTable.orderTime

    /** Total order amount with high precision */
    var orderTotal by OrdersTable.orderTotal

    /** Order status for lifecycle tracking */
    var orderStatus by OrdersTable.orderStatus

    /** Reference to TSE transaction if signed */
    var tseTransaction by TseTransaction optionalReferencedOn OrdersTable.tseTransactionId

    /** See [OrdersTable.zBonId] — the Kassenabschluss this Vorgang belongs to. */
    var zBon by org.example.project.features.zBon.domain.models.ZBon optionalReferencedOn OrdersTable.zBonId

    /** Order items in this order (uses optionalReferrersOn since order reference is nullable) */
    val items by OrderItem optionalReferrersOn OrderItemTable.order

    /** TSE signing status for queue-based signing (Nachsignieren) */
    var tseSigningStatus by OrdersTable.tseSigningStatus

    /** Training mode flag - orders in training mode are not fiscally relevant */
    var isTraining by OrdersTable.isTraining

    /** Reference to terminal that created this order */
    var terminal by Terminal optionalReferencedOn OrdersTable.terminalId
}
