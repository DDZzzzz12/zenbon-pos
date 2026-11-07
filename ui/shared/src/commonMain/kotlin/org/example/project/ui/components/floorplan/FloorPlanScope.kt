package org.example.project.ui.components.floorplan

import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import org.example.project.core.data.models.RTable
import org.example.project.core.data.models.RoomElement
import org.example.project.core.utils.FloorPlanPlacement
import org.example.project.core.utils.GridCellDimensions
import kotlin.math.roundToInt

/**
 * Placement API for children of a [PosFloorPlanSurface].
 *
 * A caller renders whatever it likes and positions it with one of the `*Cell` modifiers; the
 * surface owns the geometry, the caller owns the gestures. That split is why the till's combine
 * drag and the editor's move drag can coexist without either app's concerns leaking into shared-ui.
 */
@Stable
interface FloorPlanScope {
    /** Cell dimensions derived from the surface's own constraints. */
    val cells: GridCellDimensions

    /** Current zoom. Divide a raw drag delta by this before converting it to grid coordinates. */
    val zoom: Float

    /** Places and sizes a table, applying its rotation and the till's circular-table rule. */
    fun Modifier.tableCell(table: RTable): Modifier

    /** Places and sizes a room element, applying its rotation. */
    fun Modifier.elementCell(element: RoomElement): Modifier

    /** Places and sizes an arbitrary grid-positioned overlay. */
    fun Modifier.gridCell(
        gridX: Int,
        gridY: Int,
        wCells: Int,
        hCells: Int,
        rotation: Float = 0f,
        squareFromWidth: Boolean = false,
    ): Modifier
}

/**
 * The scope's only implementation.
 *
 * Placement uses [Modifier.layout] rather than `offset` + `size`, because the pixel rect comes from
 * [FloorPlanPlacement] as floats and forcing it through dp would round twice — once into dp and
 * again back into pixels — visibly detaching a cell from its grid line at high zoom.
 */
internal class FloorPlanScopeImpl(
    override val cells: GridCellDimensions,
    override val zoom: Float,
) : FloorPlanScope {
    override fun Modifier.tableCell(table: RTable): Modifier =
        gridCell(
            gridX = table.gridX ?: 0,
            gridY = table.gridY ?: 0,
            wCells = FloorPlanPlacement.tableSpan(table.gridWidth),
            hCells = FloorPlanPlacement.tableSpan(table.gridHeight),
            rotation = table.rotation ?: 0f,
            squareFromWidth = table.isCircular(),
        )

    override fun Modifier.elementCell(element: RoomElement): Modifier =
        gridCell(
            gridX = element.gridX,
            gridY = element.gridY,
            wCells = element.gridWidth,
            hCells = element.gridHeight,
            rotation = element.rotation,
        )

    override fun Modifier.gridCell(
        gridX: Int,
        gridY: Int,
        wCells: Int,
        hCells: Int,
        rotation: Float,
        squareFromWidth: Boolean,
    ): Modifier {
        val bounds =
            FloorPlanPlacement.bounds(
                gridX = gridX,
                gridY = gridY,
                wCells = wCells,
                hCells = hCells,
                cells = cells,
                squareFromWidth = squareFromWidth,
            )
        return this
            .layout { measurable, _ ->
                // Round the EDGES, not the offset and the size independently. Cell dimensions are
                // fractional (paneWidth / 80), so rounding position and size separately lets two
                // items that share a grid edge disagree by a pixel — a visible seam between
                // adjacent wall segments, or a 1 px overlap. Deriving the size from two rounded
                // edges makes abutting items share an exact integer boundary at every zoom.
                val left = bounds.x.roundToInt()
                val top = bounds.y.roundToInt()
                val width = ((bounds.x + bounds.width).roundToInt() - left).coerceAtLeast(0)
                val height = ((bounds.y + bounds.height).roundToInt() - top).coerceAtLeast(0)
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) {
                    placeable.place(IntOffset(left, top))
                }
            }
            // Rotate the visual only. The measured box stays axis-aligned so drag, snap and the
            // stored grid position are unaffected by rotation.
            .graphicsLayer { rotationZ = rotation }
    }
}
