package org.example.project.core.data.database

import org.example.project.core.data.models.RTable
import org.example.project.database.Local_r_tables
import org.example.project.database.PosDatabase

/** Local SQLDelight cache access for restaurant tables. Synchronous, like the other DataManagers. */
class RTableDataManager(
    private val database: PosDatabase,
) {
    private val q get() = database.localRTablesQueries

    fun upsertAll(tables: List<RTable>) {
        database.transaction { tables.forEach { upsertRow(it) } }
    }

    /** Atomically replace all rows for [locationTypeId] in a single transaction (no empty-cache window). */
    fun replaceByLocationType(
        locationTypeId: Long,
        tables: List<RTable>,
    ) {
        database.transaction {
            q.deleteByLocationType(locationTypeId)
            tables.forEach { upsertRow(it) }
        }
    }

    /** Atomically replace the entire cache in a single transaction (no empty-cache window). */
    fun replaceAll(tables: List<RTable>) {
        database.transaction {
            q.clear()
            tables.forEach { upsertRow(it) }
        }
    }

    /** Upserts a single row. MUST run inside an existing [PosDatabase.transaction]. */
    private fun upsertRow(t: RTable) {
        q.upsert(
            id = t.id,
            table_number = t.tableNumber,
            seats = t.seats,
            location_type_id = t.locationTypeId,
            location_type_name = t.locationTypeName,
            is_virtual = if (t.isVirtual) 1L else 0L,
            is_takeaway = if (t.isTakeaway) 1L else 0L,
            table_status = t.tableStatus,
            current_session_id = t.currentSessionId,
            current_employee_number = t.currentEmployeeNumber,
            grid_x = t.gridX?.toLong(),
            grid_y = t.gridY?.toLong(),
            rotation = t.rotation?.toDouble(),
            grid_width = t.gridWidth?.toLong(),
            grid_height = t.gridHeight?.toLong(),
            is_training = if (t.isTraining) 1L else 0L,
            is_active = if (t.isActive) 1L else 0L,
            assignment_priority = t.assignmentPriority.toLong(),
            open_amount = t.openAmount,
        )
    }

    fun selectByLocationType(locationTypeId: Long): List<Local_r_tables> = q.selectByLocationType(locationTypeId).executeAsList()

    fun selectById(id: Long): Local_r_tables? = q.selectById(id).executeAsOneOrNull()

    fun deleteById(id: Long) = q.deleteById(id)

    fun deleteByLocationType(locationTypeId: Long) = q.deleteByLocationType(locationTypeId)

    fun clear() = q.clear()
}
