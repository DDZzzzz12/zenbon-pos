package org.example.project.core.data.database

import org.example.project.core.data.models.RoomElement
import org.example.project.database.Local_room_elements
import org.example.project.database.PosDatabase

/** Local SQLDelight cache access for room elements. Synchronous, like the other DataManagers. */
class RoomElementDataManager(
    private val database: PosDatabase,
) {
    private val q get() = database.localRoomElementsQueries

    fun upsertAll(elements: List<RoomElement>) {
        database.transaction { elements.forEach { upsertRow(it) } }
    }

    /** Atomically replace all rows for [locationTypeId] in a single transaction (no empty-cache window). */
    fun replaceByLocationType(
        locationTypeId: Long,
        elements: List<RoomElement>,
    ) {
        database.transaction {
            q.deleteByLocationType(locationTypeId)
            elements.forEach { upsertRow(it) }
        }
    }

    /** Atomically replace the entire cache in a single transaction (no empty-cache window). */
    fun replaceAll(elements: List<RoomElement>) {
        database.transaction {
            q.clear()
            elements.forEach { upsertRow(it) }
        }
    }

    /** Upserts a single row. MUST run inside an existing [PosDatabase.transaction]. */
    private fun upsertRow(e: RoomElement) {
        q.upsert(
            id = e.id,
            element_type = e.elementType,
            grid_x = e.gridX.toLong(),
            grid_y = e.gridY.toLong(),
            grid_width = e.gridWidth.toLong(),
            grid_height = e.gridHeight.toLong(),
            rotation = e.rotation.toDouble(),
            location_type_id = e.locationTypeId,
            metadata = e.metadata,
        )
    }

    fun selectByLocationType(locationTypeId: Long): List<Local_room_elements> = q.selectByLocationType(locationTypeId).executeAsList()

    fun selectById(id: Long): Local_room_elements? = q.selectById(id).executeAsOneOrNull()

    fun deleteById(id: Long) = q.deleteById(id)

    fun deleteByLocationType(locationTypeId: Long) = q.deleteByLocationType(locationTypeId)

    fun clear() = q.clear()
}
