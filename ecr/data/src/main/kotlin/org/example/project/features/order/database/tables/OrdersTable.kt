/**
 * Exposed table definition for orders.
 *
 * Defines the database schema structure for customer orders with proper
 * column types, constraints, and relationships. Central table for order
 * management and tracking in the point-of-sale system.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.order.database.tables

import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.table.database.tables.TableSessionTable
import org.example.project.features.terminal.database.tables.TerminalTable
import org.example.project.features.tse.database.tables.TseTransactionTable
import org.example.project.features.zBon.database.tables.ZBonTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Exposed table definition for t_order table.
 *
 * Manages customer orders including order tracking, table assignment,
 * and order totals. Links orders to employees and tables for comprehensive
 * order management.
 */
object OrdersTable : LongIdTable("t_order", "id") {
    /** Unique order identifier for business operations */
    val orderNumber = varchar("order_number", 100).uniqueIndex()

    /** Reference to employee who created the order */
    val employeeId = reference("employee_id", EmployeeTable)

    /** Reference to table where order was placed */
    val tableSessionId = reference("table_session_id", TableSessionTable)

    /** Reference to order status **/
    val orderStatus = enumerationByName("order_status", 50, OrderStatus::class)

    /** Timestamp when order was created */
    val orderTime = datetime("order_time")

    /** Total order amount with high precision */
    val orderTotal = decimal("order_total", precision = 19, scale = 4)

    /** Reference to TSE transaction if signed */
    val tseTransactionId = reference("tse_transaction_id", TseTransactionTable).nullable()

    /**
     * The Kassenabschluss this Vorgang belongs to.
     *
     * `orders` was the only fiscal source without this link: bills, cancellations and Kassenbuch
     * entries all RECORD their closing, while orders were attributed by window arithmetic
     * (`closingFor(zBons, orderTime)`) at export time. An order falling in a GAP between two
     * closings — the shop failed to close a day — is inside the export's query window but covered
     * by none, and `generateExport` dropped it silently, even though it was TSE-signed as an
     * AVBestellung. That leaves a signature in the WORM log the export cannot explain (Tz. 2.1).
     *
     * Recording it also stops a re-export re-attributing the Vorgang to a different Z_NR if closing
     * windows ever change.
     */
    val zBonId = reference("zbon_id", ZBonTable).nullable()

    /** TSE signing status for tracking queue-based signing (Nachsignieren) */
    val tseSigningStatus =
        enumerationByName<TseSigningStatus>("tse_signing_status", 20)
            .default(TseSigningStatus.PENDING)

    /** Training mode flag - orders in training mode are not fiscally relevant */
    val isTraining = bool("is_training").default(false)

    /** Reference to terminal that created this order */
    val terminalId = reference("terminal_id", TerminalTable).nullable()

    init {
        // Query hot paths — kept in sync with Flyway V84 (matching index names).
        index("idx_order_table_session", false, tableSessionId)
        index("idx_order_employee", false, employeeId)
        index("idx_t_order_zbon_id", false, zBonId)
    }
}
