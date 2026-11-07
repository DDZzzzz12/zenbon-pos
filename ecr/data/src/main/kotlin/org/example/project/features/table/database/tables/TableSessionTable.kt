package org.example.project.features.table.database.tables

import org.example.project.features.customer.database.tables.CustomerAddressTable
import org.example.project.features.customer.database.tables.CustomerTable
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.tse.database.tables.TseTransactionTable
import org.example.project.features.zBon.database.tables.ZBonTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Database table for table sessions.
 *
 * Tracks customer sessions at tables, including dine-in and delivery orders.
 * A session represents the period from when a table is opened until it's closed
 * and paid, or for delivery orders from placement to completion.
 *
 * Features:
 * - Dine-in mode: Traditional table service with physical table reference
 * - Delivery mode: Delivery orders with customer and address information
 * - Session lifecycle tracking (start time, end time, completion status)
 * - Employee assignment for accountability
 * - Integration with Z-Bon (daily closing reports)
 * - Optional customer association for loyalty programs
 *
 * Relationships:
 * - Many-to-One with RTableTable (physical table for dine-in)
 * - Many-to-One with CustomerTable (optional, for loyalty and delivery)
 * - Many-to-One with CustomerAddressTable (delivery address for delivery mode)
 * - Many-to-One with EmployeeTable (employee serving this session)
 * - Many-to-One with ZBonTable (daily closing report)
 * - One-to-Many with OrdersTable (orders placed during session)
 * - One-to-Many with BillTable (bills created from session)
 *
 * @property id Long primary key (table_session_id)
 * @property table Reference to physical table (for dine-in)
 * @property employeeId Employee responsible for this session
 * @property startTime When session started
 * @property endTime When session ended (nullable if still active)
 * @property completed Whether session is completed and closed
 * @property zBonId Reference to Z-Bon (daily closing report)
 * @property note Optional notes for the session
 * @property delivery Boolean flag indicating if this is a delivery order
 * @property customer Optional reference to customer (required for delivery, optional for dine-in loyalty)
 * @property deliveryAddress Reference to delivery address (required if delivery=true)
 * @property deliveryPhoneNumber Contact phone for delivery (optional override)
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
object TableSessionTable : LongIdTable("table_session", "table_session_id") {
    val table = reference("table_id", RTableTable)
    val employeeId = reference("employee_id", EmployeeTable)

    /**
     * Session start — stamped PER INSERT, and fiscally live: a bill's `startTime` is this value,
     * which becomes the DSFinV-K `BON_START`.
     *
     * MUST stay `clientDefault { }`. `Column.default(value)` evaluates its argument once at
     * table-object init, which froze one wall-clock instant across every row (audit KABS-1).
     * Not `defaultExpression(CurrentDateTime)` either — that uses the DB server's timezone
     * instead of the shop-configured timeProvider.
     */
    val startTime = datetime("start_time").clientDefault { currentTimeStamp() }
    val endTime = datetime("end_time").nullable()
    val completed = bool("completed").default(false)
    val zBonId = reference("zbon_id", ZBonTable).nullable()
    val note = varchar("note", 500).nullable()

    /** Session type: DINING (normal table service), QUICK_SALE (direct sales at counter), DELIVERY */
    val sessionType = varchar("session_type", 20).default("DINING")

    /**
     * Optional reference to a TSE transaction. Written only by the removed quick-sale flow and
     * read only by its orphan-cleanup service, so nothing populates it any more.
     *
     * **Deliberately retained — do not drop.** It is a foreign key into a TSE table, and historical
     * quick-sale rows can still carry a real session-to-TSE link that has to stay readable for the
     * retention period. Dropping it buys nothing functionally. Removing the Kotlin property alone
     * would be worse than useless: the schema comes from Exposed `SchemaUtils.create`, which creates
     * columns but never drops them, so fresh databases would silently diverge from existing ones.
     */
    val tseTransactionId = reference("tse_transaction_id", TseTransactionTable).nullable()

    /** Training mode flag - sessions created by training employees are isolated from production */
    val isTraining = bool("is_training").default(false)

    // Delivery Support
    val delivery = bool("delivery").default(false)
    val customer = reference("customer_id", CustomerTable).nullable()
    val deliveryAddress = reference("delivery_address_id", CustomerAddressTable).nullable()
    val deliveryPhoneNumber = varchar("delivery_phone_number", 20).nullable()

    init {
        // Query hot paths — kept in sync with Flyway V86 (matching index names so fresh
        // installs (Exposed-created schema) and existing installs (migration) converge).
        index("idx_table_session_table_completed", false, table, completed)
        index("idx_table_session_employee", false, employeeId)
        index("idx_table_session_start_time", false, startTime)
    }
}
