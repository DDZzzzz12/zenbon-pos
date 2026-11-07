package org.example.project.ui.components.floorplan

import org.example.project.core.data.models.RTable
import org.example.project.core.data.models.RoomElement
import org.example.project.core.utils.FloorPlanPlacement
import org.example.project.core.utils.GridConverter
import org.example.project.core.utils.GridDefaults

/**
 * An axis-aligned rectangle in grid-cell units (not pixels): [left]/[top] inclusive,
 * [right]/[bottom] exclusive.
 */
data class FloorPlanContentBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        /** The 80 x 55 grid itself; what a plan with no content fits to. */
        val GRID =
            FloorPlanContentBounds(
                left = 0f,
                top = 0f,
                right = GridDefaults.GRID_COLUMNS.toFloat(),
                bottom = GridDefaults.GRID_ROWS.toFloat(),
            )

        /**
         * The bounding box of what the plan actually DRAWS for [tables] and [elements]: each item
         * clamped onto the grid exactly as [FloorPlanPlacement] places it, tables floored at
         * [FloorPlanPlacement.MIN_TABLE_CELLS]. Rotation is ignored (a rotated item is drawn around its
         * own centre, so the box is close enough to frame it). Null when there is nothing to frame, so
         * the caller can fall back to [GRID].
         */
        fun of(
            tables: List<RTable>,
            elements: List<RoomElement>,
        ): FloorPlanContentBounds? {
            val boxes =
                tables.map { table ->
                    val w = FloorPlanPlacement.tableSpan(table.gridWidth)
                    // A circular table is drawn square from its width.
                    val h = if (table.isCircular()) w else FloorPlanPlacement.tableSpan(table.gridHeight)
                    GridConverter.clampToGrid(table.gridX ?: 0, table.gridY ?: 0, w, h)
                } +
                    elements.map { element ->
                        GridConverter.clampToGrid(element.gridX, element.gridY, element.gridWidth, element.gridHeight)
                    }
            if (boxes.isEmpty()) return null
            return FloorPlanContentBounds(
                left = boxes.minOf { it.gridX }.toFloat(),
                top = boxes.minOf { it.gridY }.toFloat(),
                right = boxes.maxOf { it.gridX + it.gridWidth }.toFloat(),
                bottom = boxes.maxOf { it.gridY + it.gridHeight }.toFloat(),
            )
        }
    }
}
