/**
 * Grid-to-pixel coordinate conversion utility.
 *
 * Handles bidirectional conversion between grid coordinates (integer positions)
 * and pixel coordinates (actual screen positions). Automatically calculates
 * cell size based on canvas dimensions while maintaining grid aspect ratio.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.utils

import kotlin.math.round

/**
 * Converter between grid coordinates and pixel positions.
 *
 * The grid system uses fixed dimensions (80x50) that scale to fit any
 * screen size. This utility calculates the appropriate cell size and
 * performs conversions between grid and pixel space.
 */
object GridConverter {
    /**
     * Calculate grid cell dimensions based on canvas dimensions.
     *
     * Determines the width and height of each grid cell independently,
     * allowing rectangular cells that fully utilize the canvas space.
     * This provides better space usage for non-square canvases.
     *
     * @param canvasWidth Canvas width in pixels
     * @param canvasHeight Canvas height in pixels
     * @return GridCellDimensions with separate width and height
     */
    fun calculateCellDimensions(
        canvasWidth: Float,
        canvasHeight: Float,
    ): GridCellDimensions {
        if (canvasWidth <= 0f || canvasHeight <= 0f) {
            return GridCellDimensions(0f, 0f)
        }

        // Calculate cell dimensions independently for full canvas utilization
        val cellWidth =
            (canvasWidth / GridDefaults.GRID_COLUMNS).coerceIn(
                GridDefaults.Visualization.MIN_CELL_SIZE_PX,
                GridDefaults.Visualization.MAX_CELL_SIZE_PX,
            )

        val cellHeight =
            (canvasHeight / GridDefaults.GRID_ROWS).coerceIn(
                GridDefaults.Visualization.MIN_CELL_SIZE_PX,
                GridDefaults.Visualization.MAX_CELL_SIZE_PX,
            )

        return GridCellDimensions(cellWidth, cellHeight)
    }

    /**
     * Convert grid X coordinate to pixel X position.
     *
     * @param gridX Grid column (0 to 79)
     * @param cellSize Cell size in pixels
     * @return Pixel X position
     */
    fun gridToPixelX(
        gridX: Int,
        cellSize: Float,
    ): Float = gridX * cellSize

    /**
     * Convert grid X coordinate to pixel X position (rectangular cells).
     *
     * @param gridX Grid column (0 to 79)
     * @param cellWidth Cell width in pixels
     * @return Pixel X position
     */
    fun gridToPixelX(
        gridX: Int,
        cellWidth: Float,
        @Suppress("UNUSED_PARAMETER") useWidth: Boolean = true,
    ): Float = gridX * cellWidth

    /**
     * Convert grid Y coordinate to pixel Y position.
     *
     * @param gridY Grid row (0 to 49)
     * @param cellSize Cell size in pixels
     * @return Pixel Y position
     */
    fun gridToPixelY(
        gridY: Int,
        cellSize: Float,
    ): Float = gridY * cellSize

    /**
     * Convert grid Y coordinate to pixel Y position (rectangular cells).
     *
     * @param gridY Grid row (0 to 49)
     * @param cellHeight Cell height in pixels
     * @return Pixel Y position
     */
    fun gridToPixelY(
        gridY: Int,
        cellWidth: Float,
        cellHeight: Float,
    ): Float = gridY * cellHeight

    /**
     * Convert grid width to pixel width.
     *
     * @param gridWidth Width in grid cells
     * @param cellSize Cell size in pixels
     * @return Pixel width
     */
    fun gridToPixelWidth(
        gridWidth: Int,
        cellSize: Float,
    ): Float = gridWidth * cellSize

    /**
     * Convert grid width to pixel width (rectangular cells).
     *
     * @param gridWidth Width in grid cells
     * @param cellWidth Cell width in pixels
     * @return Pixel width
     */
    fun gridToPixelWidth(
        gridWidth: Int,
        cellWidth: Float,
        @Suppress("UNUSED_PARAMETER") useWidth: Boolean = true,
    ): Float = gridWidth * cellWidth

    /**
     * Convert grid height to pixel height.
     *
     * @param gridHeight Height in grid cells
     * @param cellSize Cell size in pixels
     * @return Pixel height
     */
    fun gridToPixelHeight(
        gridHeight: Int,
        cellSize: Float,
    ): Float = gridHeight * cellSize

    /**
     * Convert grid height to pixel height (rectangular cells).
     *
     * @param gridHeight Height in grid cells
     * @param cellHeight Cell height in pixels
     * @return Pixel height
     */
    fun gridToPixelHeight(
        gridHeight: Int,
        cellWidth: Float,
        cellHeight: Float,
    ): Float = gridHeight * cellHeight

    /**
     * Convert pixel X position to grid X coordinate with snapping.
     *
     * Rounds to nearest grid column. Useful for drag-and-drop operations
     * where you want elements to snap to grid positions.
     *
     * @param pixelX Pixel X position
     * @param cellSize Cell size in pixels
     * @return Grid column (clamped to 0..GRID_COLUMNS-1)
     */
    fun pixelToGridX(
        pixelX: Float,
        cellSize: Float,
    ): Int {
        if (cellSize <= 0f) return 0
        val gridX = round(pixelX / cellSize).toInt()
        return gridX.coerceIn(0, GridDefaults.GRID_COLUMNS - 1)
    }

    /**
     * Convert pixel X position to grid X coordinate with snapping (rectangular cells).
     *
     * @param pixelX Pixel X position
     * @param cellWidth Cell width in pixels
     * @return Grid column (clamped to 0..GRID_COLUMNS-1)
     */
    fun pixelToGridX(
        pixelX: Float,
        cellWidth: Float,
        @Suppress("UNUSED_PARAMETER") useWidth: Boolean = true,
    ): Int {
        if (cellWidth <= 0f) return 0
        val gridX = round(pixelX / cellWidth).toInt()
        return gridX.coerceIn(0, GridDefaults.GRID_COLUMNS - 1)
    }

    /**
     * Convert pixel Y position to grid Y coordinate with snapping.
     *
     * Rounds to nearest grid row. Useful for drag-and-drop operations
     * where you want elements to snap to grid positions.
     *
     * @param pixelY Pixel Y position
     * @param cellSize Cell size in pixels
     * @return Grid row (clamped to 0..GRID_ROWS-1)
     */
    fun pixelToGridY(
        pixelY: Float,
        cellSize: Float,
    ): Int {
        if (cellSize <= 0f) return 0
        val gridY = round(pixelY / cellSize).toInt()
        return gridY.coerceIn(0, GridDefaults.GRID_ROWS - 1)
    }

    /**
     * Convert pixel Y position to grid Y coordinate with snapping (rectangular cells).
     *
     * @param pixelY Pixel Y position
     * @param cellHeight Cell height in pixels
     * @return Grid row (clamped to 0..GRID_ROWS-1)
     */
    fun pixelToGridY(
        pixelY: Float,
        cellWidth: Float,
        cellHeight: Float,
    ): Int {
        if (cellHeight <= 0f) return 0
        val gridY = round(pixelY / cellHeight).toInt()
        return gridY.coerceIn(0, GridDefaults.GRID_ROWS - 1)
    }

    /**
     * Convert pixel width to grid width (rounded).
     *
     * @param pixelWidth Pixel width
     * @param cellSize Cell size in pixels
     * @return Width in grid cells (minimum 1)
     */
    fun pixelToGridWidth(
        pixelWidth: Float,
        cellSize: Float,
    ): Int {
        if (cellSize <= 0f) return 1
        val gridWidth = round(pixelWidth / cellSize).toInt()
        return gridWidth.coerceAtLeast(1)
    }

    /**
     * Convert pixel width to grid width (rounded, rectangular cells).
     *
     * @param pixelWidth Pixel width
     * @param cellWidth Cell width in pixels
     * @return Width in grid cells (minimum 1)
     */
    fun pixelToGridWidth(
        pixelWidth: Float,
        cellWidth: Float,
        @Suppress("UNUSED_PARAMETER") useWidth: Boolean = true,
    ): Int {
        if (cellWidth <= 0f) return 1
        val gridWidth = round(pixelWidth / cellWidth).toInt()
        return gridWidth.coerceAtLeast(1)
    }

    /**
     * Convert pixel height to grid height (rounded).
     *
     * @param pixelHeight Pixel height
     * @param cellSize Cell size in pixels
     * @return Height in grid cells (minimum 1)
     */
    fun pixelToGridHeight(
        pixelHeight: Float,
        cellSize: Float,
    ): Int {
        if (cellSize <= 0f) return 1
        val gridHeight = round(pixelHeight / cellSize).toInt()
        return gridHeight.coerceAtLeast(1)
    }

    /**
     * Convert pixel height to grid height (rounded, rectangular cells).
     *
     * @param pixelHeight Pixel height
     * @param cellHeight Cell height in pixels
     * @return Height in grid cells (minimum 1)
     */
    fun pixelToGridHeight(
        pixelHeight: Float,
        cellWidth: Float,
        cellHeight: Float,
    ): Int {
        if (cellHeight <= 0f) return 1
        val gridHeight = round(pixelHeight / cellHeight).toInt()
        return gridHeight.coerceAtLeast(1)
    }

    /**
     * Check if grid coordinates are within valid bounds.
     *
     * @param gridX Grid column
     * @param gridY Grid row
     * @return True if coordinates are valid
     */
    fun isValidGridPosition(
        gridX: Int,
        gridY: Int,
    ): Boolean =
        gridX in 0 until GridDefaults.GRID_COLUMNS &&
            gridY in 0 until GridDefaults.GRID_ROWS

    /**
     * Check if element fits within grid bounds.
     *
     * @param gridX Starting grid column
     * @param gridY Starting grid row
     * @param gridWidth Width in grid cells
     * @param gridHeight Height in grid cells
     * @return True if element fits completely within grid
     */
    fun fitsInGrid(
        gridX: Int,
        gridY: Int,
        gridWidth: Int,
        gridHeight: Int,
    ): Boolean =
        gridX >= 0 &&
            gridY >= 0 &&
            (gridX + gridWidth) <= GridDefaults.GRID_COLUMNS &&
            (gridY + gridHeight) <= GridDefaults.GRID_ROWS

    /**
     * Clamp element to fit within grid bounds.
     *
     * The size is kept (only capped at the whole grid) and the POSITION moves inward so the whole
     * item stays on the grid. Shrinking the size instead turned a table stored past the last column
     * (e.g. pixel-like coordinates from a script) into an invisible one-cell sliver (UI-E1-13).
     *
     * @param gridX Starting grid column
     * @param gridY Starting grid row
     * @param gridWidth Width in grid cells
     * @param gridHeight Height in grid cells
     * @return Clamped coordinates as (gridX, gridY, gridWidth, gridHeight)
     */
    fun clampToGrid(
        gridX: Int,
        gridY: Int,
        gridWidth: Int,
        gridHeight: Int,
    ): GridBounds {
        val clampedWidth = gridWidth.coerceIn(1, GridDefaults.GRID_COLUMNS)
        val clampedHeight = gridHeight.coerceIn(1, GridDefaults.GRID_ROWS)

        val clampedX = gridX.coerceIn(0, GridDefaults.GRID_COLUMNS - clampedWidth)
        val clampedY = gridY.coerceIn(0, GridDefaults.GRID_ROWS - clampedHeight)

        return GridBounds(clampedX, clampedY, clampedWidth, clampedHeight)
    }
}

/**
 * Grid bounds data class for clamped coordinates.
 *
 * @property gridX Grid column
 * @property gridY Grid row
 * @property gridWidth Width in grid cells
 * @property gridHeight Height in grid cells
 */
data class GridBounds(
    val gridX: Int,
    val gridY: Int,
    val gridWidth: Int,
    val gridHeight: Int,
)

/**
 * Grid cell dimensions for rectangular cells.
 *
 * Stores separate width and height values for grid cells,
 * allowing full utilization of rectangular canvas space.
 *
 * @property cellWidth Width of each grid cell in pixels
 * @property cellHeight Height of each grid cell in pixels
 */
data class GridCellDimensions(
    val cellWidth: Float,
    val cellHeight: Float,
) {
    /**
     * Get average cell size (for backward compatibility with square cell logic).
     */
    val averageSize: Float
        get() = (cellWidth + cellHeight) / 2f
}
