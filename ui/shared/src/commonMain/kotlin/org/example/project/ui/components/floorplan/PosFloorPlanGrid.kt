package org.example.project.ui.components.floorplan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.example.project.core.utils.GridCellDimensions
import org.example.project.core.utils.GridDefaults

/** Below this on-screen cell size the minor guides are omitted, or the plan turns to mush. */
private const val MIN_MINOR_GUIDE_CELL_PX = 6f

/** Every fifth guide is drawn heavier, so a cell can be counted off at a glance. */
private const val MAJOR_GUIDE_EVERY = 5

/**
 * The floor plan's background: the plan surface, its alignment guides, and a border marking the
 * bounds of the 80 × 55 grid.
 *
 * Draw this **inside** the same transformed box as the cells. Painting it in screen space and
 * re-deriving pan/zoom by hand is what made the editor's dots drift off their cells at any zoom
 * other than 100 % — a `graphicsLayer` scales about its centre, while hand-rolled dot maths assumes
 * the origin.
 *
 * @param cells Cell dimensions for the current viewport
 * @param zoom Current zoom, used only to decide whether minor guides stay legible
 */
@Composable
fun PosFloorPlanGrid(
    cells: GridCellDimensions,
    modifier: Modifier = Modifier,
    style: FloorPlanGridStyle = FloorPlanGridStyle.Dots,
    zoom: Float = 1f,
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val minorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    val majorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val planWidth = GridDefaults.GRID_COLUMNS * cells.cellWidth
            val planHeight = GridDefaults.GRID_ROWS * cells.cellHeight
            if (planWidth <= 0f || planHeight <= 0f) return@Canvas

            drawRect(color = surfaceColor, size = Size(planWidth, planHeight))

            val drawMinor = cells.cellWidth * zoom >= MIN_MINOR_GUIDE_CELL_PX
            when (style) {
                FloorPlanGridStyle.Dots ->
                    for (col in 0..GridDefaults.GRID_COLUMNS) {
                        for (row in 0..GridDefaults.GRID_ROWS) {
                            val major = col % MAJOR_GUIDE_EVERY == 0 && row % MAJOR_GUIDE_EVERY == 0
                            if (!major && !drawMinor) continue
                            drawCircle(
                                color = if (major) majorColor else minorColor,
                                radius = 1.5f,
                                center = Offset(col * cells.cellWidth, row * cells.cellHeight),
                            )
                        }
                    }

                FloorPlanGridStyle.Lines -> {
                    for (col in 0..GridDefaults.GRID_COLUMNS) {
                        val major = col % MAJOR_GUIDE_EVERY == 0
                        if (!major && !drawMinor) continue
                        val x = col * cells.cellWidth
                        drawLine(
                            color = if (major) majorColor else minorColor,
                            start = Offset(x, 0f),
                            end = Offset(x, planHeight),
                            strokeWidth = if (major) 1.5f else 1f,
                        )
                    }
                    for (row in 0..GridDefaults.GRID_ROWS) {
                        val major = row % MAJOR_GUIDE_EVERY == 0
                        if (!major && !drawMinor) continue
                        val y = row * cells.cellHeight
                        drawLine(
                            color = if (major) majorColor else minorColor,
                            start = Offset(0f, y),
                            end = Offset(planWidth, y),
                            strokeWidth = if (major) 1.5f else 1f,
                        )
                    }
                }
            }

            // The plan's edge. Without it the editor gives no clue where the till stops drawing.
            drawRect(
                color = borderColor,
                size = Size(planWidth, planHeight),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}
