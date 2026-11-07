package org.example.project.features.table.database.tables

import org.example.project.core.domain.enums.room.RoomElementType
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Database table definition for room layout elements.
 *
 * Stores floor plan elements such as walls, doors, windows, and counters
 * for restaurant layout visualization and interactive floor plan editing.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Table definition for room layout elements.
 *
 * Contains position, dimensions, and metadata for structural and
 * decorative elements that make up the restaurant floor plan.
 */
object RoomElementTable : LongIdTable("room_element", "element_id") {
    /** Type of element (WALL, DOOR, WINDOW, COUNTER, DECORATION) */
    val elementType = enumerationByName("element_type", 50, RoomElementType::class)

    /** X coordinate position on floor plan canvas */
    val positionX = float("position_x")

    /** Y coordinate position on floor plan canvas */
    val positionY = float("position_y")

    /** Width of the element in pixels/units */
    val width = float("width")

    /** Height of the element in pixels/units */
    val height = float("height")

    /** Rotation angle in degrees (0-360) */
    val rotation = float("rotation").default(0f)

    /** Location/branch identifier for multi-location support */
    val locationTypeId = reference("location_type_id", LocationTypeTable)

    /** JSON metadata for element-specific properties (color, thickness, label, etc.) */
    val metadata = text("metadata").nullable()

    /** Timestamp when element was created */
    val createdAt = datetime("created_at")

    /** Timestamp when element was last updated */
    val updatedAt = datetime("updated_at")
}
