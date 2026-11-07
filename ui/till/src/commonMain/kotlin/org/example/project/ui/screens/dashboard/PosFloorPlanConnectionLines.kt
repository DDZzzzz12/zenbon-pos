package org.example.project.ui.screens.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import org.example.project.core.data.models.RTable
import org.example.project.core.utils.GridCellDimensions
import org.example.project.ui.theme.PosTheme

/**
 * Renders connection lines between combinable tables and the active drag line.
 * Drawn as a Canvas layer on the floor plan, between the grid and table cards.
 */
@Composable
fun PosFloorPlanConnectionLines(
    tables: List<RTable>,
    connections: Map<Long, Set<Long>>,
    combiningFromTableId: Long?,
    combineLineEndX: Float,
    combineLineEndY: Float,
    combineHoverTableId: Long?,
    cellDimensions: GridCellDimensions,
    modifier: Modifier = Modifier,
) {
    val tableCenters =
        tables.associate { table ->
            val centerX =
                (table.gridX ?: 0) * cellDimensions.cellWidth +
                    (table.gridWidth ?: 2) * cellDimensions.cellWidth / 2f
            val centerY =
                (table.gridY ?: 0) * cellDimensions.cellHeight +
                    (table.gridHeight ?: 2) * cellDimensions.cellHeight / 2f
            table.id to Offset(centerX, centerY)
        }

    val drawnPairs = mutableSetOf<Pair<Long, Long>>()

    val connectedColor = PosTheme.colors.posAvailable
    val removeColor = PosTheme.colors.posError
    val dragColor = PosTheme.colors.posReserved

    Canvas(modifier = modifier) {
        // Persistent connection lines
        for ((tableId, partners) in connections) {
            val from = tableCenters[tableId] ?: continue
            for (partnerId in partners) {
                val pair = if (tableId < partnerId) tableId to partnerId else partnerId to tableId
                if (pair in drawnPairs) continue
                drawnPairs.add(pair)

                val to = tableCenters[partnerId] ?: continue

                drawLine(
                    color = connectedColor,
                    start = from,
                    end = to,
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )

                val mid = Offset((from.x + to.x) / 2f, (from.y + to.y) / 2f)
                drawCircle(
                    color = connectedColor,
                    radius = 4f,
                    center = mid,
                )
            }
        }

        // Active drag line
        if (combiningFromTableId != null) {
            val from = tableCenters[combiningFromTableId] ?: return@Canvas
            val to = Offset(combineLineEndX, combineLineEndY)

            val isHoveringConnected =
                combineHoverTableId != null &&
                    connections[combiningFromTableId]?.contains(combineHoverTableId) == true

            val lineColor =
                when {
                    combineHoverTableId != null && isHoveringConnected -> removeColor
                    combineHoverTableId != null -> connectedColor
                    else -> dragColor
                }

            val lineWidth = if (combineHoverTableId != null) 3f else 2f

            val pathEffect =
                if (combineHoverTableId == null) {
                    PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                } else {
                    null
                }

            drawLine(
                color = lineColor,
                start = from,
                end = to,
                strokeWidth = lineWidth,
                cap = StrokeCap.Round,
                pathEffect = pathEffect,
            )
        }
    }
}
