package org.example.project.core.data.models

import org.example.project.database.Local_room_elements

/**
 * Builds a [RoomElement] from its SQLDelight row.
 *
 * Split out of `RoomElement.kt` when the model moved to `shared` so the floor-plan editor could be
 * shared with the web admin: the model is pure, but SQLDelight's generated types are composeApp-only
 * and absent on wasm. Same package, distinct filename — a same-package split must not reuse the
 * filename, or both modules emit the same facade class (runtime `NoSuchMethodError`).
 */
fun Local_room_elements.toRoomElement(): RoomElement =
    RoomElement(
        id = id,
        elementType = element_type,
        gridX = grid_x.toInt(),
        gridY = grid_y.toInt(),
        gridWidth = grid_width.toInt(),
        gridHeight = grid_height.toInt(),
        rotation = rotation.toFloat(),
        locationTypeId = location_type_id,
        metadata = metadata,
    )
