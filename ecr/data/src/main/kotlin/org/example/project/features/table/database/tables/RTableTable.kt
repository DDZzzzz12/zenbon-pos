package org.example.project.features.table.database.tables

import org.example.project.core.domain.enums.rTable.TableStatus
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

object RTableTable : LongIdTable("r_table", "table_id") {
    val tableNumber = varchar("table_number", 10).uniqueIndex()
    val seats = integer("seats").nullable()
    val locationTypeId = reference("location_type_id", LocationTypeTable)
    val tableStatus = enumerationByName(name = "table_status", 50, TableStatus::class).default(TableStatus.FREE)
    val updatedAt = datetime("created_at")

    // optional link to currently active session
    val currentSessionId = reference("current_table_session_id", TableSessionTable).nullable()

    // Floor plan position and layout fields
    val positionX = float("position_x").nullable()
    val positionY = float("position_y").nullable()
    val rotation = float("rotation").nullable()
    val width = float("width").nullable()
    val height = float("height").nullable()
    val isActive = bool("is_active").default(true)
    val assignmentPriority = integer("assignment_priority").default(0)
    val isSystem = bool("is_system").default(false)
}
