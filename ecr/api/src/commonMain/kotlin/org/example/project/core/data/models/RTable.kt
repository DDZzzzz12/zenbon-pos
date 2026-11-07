/**
 * Restaurant table data model.
 *
 * Represents a restaurant table entity for local storage and offline functionality,
 * following clean architecture principles with no external dependencies.
 *
 * ## Grid-Based Coordinate System
 * Position and size values are stored as integer grid coordinates (80x50 grid):
 * - **gridX**: Horizontal position in grid columns (0-79)
 * - **gridY**: Vertical position in grid rows (0-49)
 * - **gridWidth**: Table width in grid cells (e.g., 2 = 2 cells wide)
 * - **gridHeight**: Table height in grid cells (e.g., 2 = 2 cells tall)
 *
 * This enables responsive floor plans that automatically adapt to different screen sizes
 * by scaling grid cell size while maintaining relative layout. Grid coordinates snap
 * naturally to positions, making dragging and positioning intuitive.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.models

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.utils.GridConverter

/**
 * Restaurant table data model with grid-based positioning.
 *
 * @property gridX Grid column position (0-79), null if not positioned
 * @property gridY Grid row position (0-49), null if not positioned
 * @property gridWidth Width in grid cells, null if not configured
 * @property gridHeight Height in grid cells, null if not configured
 */
@Serializable
data class RTable(
    val id: Long,
    val tableNumber: String,
    val seats: Long,
    val locationTypeId: Long,
    val locationTypeName: String,
    val isVirtual: Boolean = false,
    val isTakeaway: Boolean = false,
    val tableStatus: String,
    val currentSessionId: Long?,
    val currentEmployeeNumber: String? = null,
    val gridX: Int?,
    val gridY: Int?,
    val rotation: Float?,
    val gridWidth: Int? = 2,
    val gridHeight: Int? = 2,
    val updated: Boolean = false,
    val isTraining: Boolean = false,
    val isActive: Boolean = true,
    val assignmentPriority: Int = 0,
    val openAmount: String? = null,
    val isSystem: Boolean = false,
) {
    companion object {
        fun fromDto(dto: RTableDto.RTableResponseDto): RTable =
            RTable(
                id = dto.id,
                tableNumber = dto.tableNumber,
                seats = dto.seats?.toLong() ?: 0L,
                locationTypeId = dto.locationTypeId,
                locationTypeName = dto.locationTypeName,
                isVirtual = dto.isVirtual,
                isTakeaway = dto.isTakeaway,
                tableStatus = dto.tableStatus,
                currentSessionId = dto.currentSessionId,
                currentEmployeeNumber = dto.currentEmployeeNumber,
                gridX = dto.positionX?.toInt(),
                gridY = dto.positionY?.toInt(),
                rotation = dto.rotation,
                gridWidth = dto.width?.toInt(),
                gridHeight = dto.height?.toInt(),
                isTraining = dto.isTraining,
                isActive = dto.isActive,
                assignmentPriority = dto.assignmentPriority,
                openAmount = dto.openAmount,
                isSystem = dto.isSystem,
            )
    }

    fun isCircular(): Boolean = gridWidth == gridHeight

    // ===== COORDINATE CONVERSION HELPERS =====

    /**
     * Converts grid X position to absolute pixels.
     *
     * @param cellSize Grid cell size in pixels
     * @return Absolute X position in pixels, or null if position not set
     */
    fun getPixelX(cellSize: Float): Float? = gridX?.let { GridConverter.gridToPixelX(it, cellSize) }

    /**
     * Converts grid Y position to absolute pixels.
     *
     * @param cellSize Grid cell size in pixels
     * @return Absolute Y position in pixels, or null if position not set
     */
    fun getPixelY(cellSize: Float): Float? = gridY?.let { GridConverter.gridToPixelY(it, cellSize) }

    /**
     * Converts grid width to absolute pixels.
     *
     * @param cellSize Grid cell size in pixels
     * @return Absolute width in pixels, or null if width not set
     */
    fun getPixelWidth(cellSize: Float): Float? = gridWidth?.let { GridConverter.gridToPixelWidth(it, cellSize) }

    /**
     * Converts grid height to absolute pixels.
     *
     * @param cellSize Grid cell size in pixels
     * @return Absolute height in pixels, or null if height not set
     */
    fun getPixelHeight(cellSize: Float): Float? = gridHeight?.let { GridConverter.gridToPixelHeight(it, cellSize) }

    /**
     * Checks if the table has valid position data.
     *
     * Rotation is not part of the position: the server stores it nullable and every renderer
     * draws a null rotation as 0°.
     *
     * @return True if table has non-null grid coordinates
     */
    fun hasPosition(): Boolean = gridX != null && gridY != null

    /**
     * Checks if the table has valid dimension data.
     *
     * @return True if table has non-null grid dimensions
     */
    fun hasDimensions(): Boolean = gridWidth != null && gridHeight != null

    /**
     * Checks if the table is fully configured for floor plan display.
     *
     * @return True if table has position, dimensions, and rotation
     */
    fun isFullyConfigured(): Boolean = hasPosition() && hasDimensions() && rotation != null

    /**
     * Checks if the table position is within valid grid bounds.
     *
     * @return True if table's grid coordinates are valid
     */
    fun isValidPosition(): Boolean =
        gridX != null &&
            gridY != null &&
            GridConverter.isValidGridPosition(gridX!!, gridY!!)

    /**
     * Checks if the table fits completely within the grid.
     *
     * @return True if table's position and size fit within grid bounds
     */
    fun fitsInGrid(): Boolean =
        gridX != null &&
            gridY != null &&
            gridWidth != null &&
            gridHeight != null &&
            GridConverter.fitsInGrid(gridX!!, gridY!!, gridWidth!!, gridHeight!!)
}
