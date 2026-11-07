package org.example.project.features.order.database.tables

import kotlinx.serialization.json.Json
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.table.database.tables.TableSessionTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime
import org.jetbrains.exposed.v1.json.jsonb

object ItemTransferTable : LongIdTable("item_transfer", "id") {
    // Legacy: populated only on rows created before V73. New code does NOT write here.
    val orderItemId = reference("order_item_id", OrderItemTable).nullable()

    // New: list of order_item ids that were moved together in one transfer event.
    val orderItemIds = jsonb<List<Long>>("order_item_ids", Json.Default).nullable()

    val employeeId = reference("employee_id", EmployeeTable)

    val fromTableSessionId = reference("from_table_id", TableSessionTable)

    val toTableSessionId = reference("to_table_id", TableSessionTable)

    val transferTime = datetime("transfer_time")

    /** Why the items were moved (German reason text or the cashier's own words); null on older rows. */
    val reason = varchar("reason", 500).nullable()

    init {
        index("idx_item_transfer_transfer_time", false, transferTime)
    }
}
