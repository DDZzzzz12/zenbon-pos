package org.example.project.features.table.domain.models

import org.example.project.features.table.database.tables.RoomElementTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Room element entity class for floor plan layout management.
 *
 * Represents structural and decorative elements on a restaurant floor plan
 * including walls, doors, windows, counters, and decorative items.
 * Supports interactive positioning, rotation, and customization.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Entity representing a floor plan element for restaurant layout.
 *
 * Manages position, dimensions, and visual properties of structural
 * and decorative elements that compose the restaurant floor plan.
 * Supports drag-and-drop repositioning in edit mode.
 */
class RoomElement(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<RoomElement>(RoomElementTable)

    /** Type of element (WALL, DOOR, WINDOW, COUNTER, DECORATION) */
    var elementType by RoomElementTable.elementType

    /** X coordinate position on floor plan canvas */
    var positionX by RoomElementTable.positionX

    /** Y coordinate position on floor plan canvas */
    var positionY by RoomElementTable.positionY

    /** Width of the element in pixels/units */
    var width by RoomElementTable.width

    /** Height of the element in pixels/units */
    var height by RoomElementTable.height

    /** Rotation angle in degrees (0-360) */
    var rotation by RoomElementTable.rotation

    /** Location type identifier (e.g., "DINING_AREA", "BAR", "PATIO") */
    var locationTypeRef by LocationType referencedOn RoomElementTable.locationTypeId

    /** JSON metadata for element-specific properties */
    var metadata by RoomElementTable.metadata

    /** Timestamp when element was created */
    var createdAt by RoomElementTable.createdAt

    /** Timestamp when element was last updated */
    var updatedAt by RoomElementTable.updatedAt

    /**
     * Updates the position of the element on the floor plan.
     *
     * @param x New X coordinate
     * @param y New Y coordinate
     */
    fun updatePosition(
        x: Float,
        y: Float,
    ) {
        positionX = x
        positionY = y
        updatedAt = currentTimeStamp()
    }

    /**
     * Updates the dimensions of the element.
     *
     * @param newWidth New width value
     * @param newHeight New height value
     */
    fun updateDimensions(
        newWidth: Float,
        newHeight: Float,
    ) {
        require(newWidth > 0) { "Width must be positive" }
        require(newHeight > 0) { "Height must be positive" }

        width = newWidth
        height = newHeight
        updatedAt = currentTimeStamp()
    }

    /**
     * Rotates the element by the specified angle.
     *
     * @param angle Rotation angle in degrees (will be normalized to 0-360)
     */
    fun rotate(angle: Float) {
        rotation = (rotation + angle) % 360f
        if (rotation < 0) rotation += 360f
        updatedAt = currentTimeStamp()
    }
}
