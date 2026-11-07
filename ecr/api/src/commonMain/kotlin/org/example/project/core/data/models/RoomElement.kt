/**
 * Room element data model for floor plan visualization.
 *
 * Represents structural and decorative elements on the restaurant floor plan
 * including walls, doors, windows, counters, and decorations.
 *
 * ## Grid-Based Coordinate System
 * Position and size values are stored as integer grid coordinates (80x50 grid):
 * - **gridX**: Horizontal position in grid columns (0-79)
 * - **gridY**: Vertical position in grid rows (0-49)
 * - **gridWidth**: Element width in grid cells (e.g., 1 = 1 cell wide)
 * - **gridHeight**: Element height in grid cells (e.g., 8 = 8 cells tall)
 *
 * This enables responsive floor plans that automatically adapt to different screen sizes
 * by scaling grid cell size while maintaining relative layout.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.models

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.table.RoomElementDto
import org.example.project.core.utils.GridConverter

/**
 * Room element data model for client-side floor plan management with grid-based positioning.
 *
 * @property gridX Grid column position (0-79)
 * @property gridY Grid row position (0-49)
 * @property gridWidth Width in grid cells
 * @property gridHeight Height in grid cells
 */
@Serializable
data class RoomElement(
    val id: Long,
    val elementType: String,
    val gridX: Int,
    val gridY: Int,
    val gridWidth: Int,
    val gridHeight: Int,
    val rotation: Float = 0f,
    val locationTypeId: Long,
    val updated: Boolean = false,
    val metadata: String? = null,
) {
    companion object {
        fun fromDto(dto: RoomElementDto.RoomElementResponseDto): RoomElement =
            RoomElement(
                id = dto.id,
                elementType = dto.elementType,
                gridX = dto.positionX.toInt(),
                gridY = dto.positionY.toInt(),
                gridWidth = dto.width.toInt(),
                gridHeight = dto.height.toInt(),
                rotation = dto.rotation,
                locationTypeId = dto.locationTypeId,
                metadata = dto.metadata,
            )
    }

    // ===== COORDINATE CONVERSION HELPERS =====

    /**
     * Converts grid X position to absolute pixels.
     *
     * @param cellSize Grid cell size in pixels
     * @return Absolute X position in pixels
     */
    fun getPixelX(cellSize: Float): Float = GridConverter.gridToPixelX(gridX, cellSize)

    /**
     * Converts grid Y position to absolute pixels.
     *
     * @param cellSize Grid cell size in pixels
     * @return Absolute Y position in pixels
     */
    fun getPixelY(cellSize: Float): Float = GridConverter.gridToPixelY(gridY, cellSize)

    /**
     * Converts grid width to absolute pixels.
     *
     * @param cellSize Grid cell size in pixels
     * @return Absolute width in pixels
     */
    fun getPixelWidth(cellSize: Float): Float = GridConverter.gridToPixelWidth(gridWidth, cellSize)

    /**
     * Converts grid height to absolute pixels.
     *
     * @param cellSize Grid cell size in pixels
     * @return Absolute height in pixels
     */
    fun getPixelHeight(cellSize: Float): Float = GridConverter.gridToPixelHeight(gridHeight, cellSize)

    /**
     * Checks if the element position is within valid grid bounds.
     *
     * @return True if element's grid coordinates are valid
     */
    fun isValidPosition(): Boolean = GridConverter.isValidGridPosition(gridX, gridY)

    /**
     * Checks if the element fits completely within the grid.
     *
     * @return True if element's position and size fit within grid bounds
     */
    fun fitsInGrid(): Boolean = GridConverter.fitsInGrid(gridX, gridY, gridWidth, gridHeight)
}
