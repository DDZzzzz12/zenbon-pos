package org.example.project.core.utils

/**
 * Converts a grid-positioned item to its absolute pixel rect on the floor plan.
 *
 * This is the single source of truth both the till and the admin editor render through, so a table
 * cannot land in a different place in the two apps. It is deliberately Compose-free: the Compose
 * `Modifier` in shared-ui is a thin wrapper over [bounds].
 */
object FloorPlanPlacement {
    /**
     * Smallest table footprint per side, in cells. The editor's resize floor; applied when drawing too,
     * so a table stored with a 0- or 1-cell span is still a visible, grabbable card.
     */
    const val MIN_TABLE_CELLS = 2

    /** A table's drawn span (cells) for one side: the stored span, defaulting to and floored at [MIN_TABLE_CELLS]. */
    fun tableSpan(cells: Int?): Int = (cells ?: MIN_TABLE_CELLS).coerceAtLeast(MIN_TABLE_CELLS)

    /**
     * Returns the absolute pixel rect for a grid-positioned item.
     *
     * @param gridX Grid column; clamped into the grid by [GridConverter.clampToGrid]
     * @param gridY Grid row; clamped the same way
     * @param wCells Width in cells; values below 1 are treated as 1
     * @param hCells Height in cells; values below 1 are treated as 1
     * @param cells Cell dimensions for the current viewport
     * @param squareFromWidth When true the rect is square, sized from cell **width** — the till's
     *   rule for a circular table, so it stays a circle on a viewport with non-square cells.
     *   Note this deliberately overrides the vertical clamp: on a viewport whose cells are wider
     *   than they are tall, a square item in the last rows extends past the plan's bottom edge.
     *   That matches what the till has always drawn, and keeping the two identical matters more
     *   than containment here.
     * @return the item's rect in plan space, before pan/zoom
     */
    fun bounds(
        gridX: Int,
        gridY: Int,
        wCells: Int,
        hCells: Int,
        cells: GridCellDimensions,
        squareFromWidth: Boolean = false,
    ): FloorPlanCellBounds {
        val clamped =
            GridConverter.clampToGrid(
                gridX = gridX,
                gridY = gridY,
                gridWidth = wCells.coerceAtLeast(1),
                gridHeight = hCells.coerceAtLeast(1),
            )
        val width = clamped.gridWidth * cells.cellWidth
        return FloorPlanCellBounds(
            x = clamped.gridX * cells.cellWidth,
            y = clamped.gridY * cells.cellHeight,
            width = width,
            height = if (squareFromWidth) width else clamped.gridHeight * cells.cellHeight,
        )
    }
}
