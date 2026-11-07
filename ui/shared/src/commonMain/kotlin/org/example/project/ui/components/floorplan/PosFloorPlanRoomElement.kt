package org.example.project.ui.components.floorplan

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TableBar
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import org.example.project.core.data.models.RoomElement
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders one non-table floor-plan element by its [RoomElement.elementType].
 *
 * Placement, size and rotation are the caller's job — pass `Modifier.elementCell(element)` from a
 * [FloorPlanScope]. This composable only fills whatever box it is given. [content] replaces the till's
 * drawing of the element (the admin editor draws the design's flat walls, doors, windows and counters).
 */
@Composable
fun PosFloorPlanRoomElement(
    element: RoomElement,
    modifier: Modifier = Modifier,
    content: (@Composable (RoomElement) -> Unit)? = null,
) {
    Box(modifier = modifier) {
        if (content != null) {
            content(element)
            return@Box
        }
        when (element.elementType) {
            "WALL" -> PosWallElement()
            "DOOR" -> PosDoorElement()
            "WINDOW" -> PosWindowElement()
            "COUNTER" -> PosCounterElement()
            else -> PosDecorationElement()
        }
    }
}

@Composable
private fun PosWallElement() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
    ) {
        // Hatched pattern overlay for wall texture
        Canvas(modifier = Modifier.fillMaxSize()) {
            val spacing = 8.dp.toPx()
            var y = 0f
            while (y < size.height) {
                drawLine(
                    color = Color.Black.copy(alpha = 0.1f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y + size.width),
                    strokeWidth = 1f,
                )
                y += spacing
            }
        }
    }
}

@Composable
private fun PosDoorElement() {
    val arcColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary),
    ) {
        // Arc indicator for door swing
        Canvas(modifier = Modifier.fillMaxSize()) {
            val arcRadius = minOf(size.width, size.height) * 0.8f

            // Draw door swing arc
            val path =
                Path().apply {
                    moveTo(0f, size.height)

                    // Arc path from bottom-left to top-right
                    val steps = 20
                    for (i in 0..steps) {
                        val angle = (i.toFloat() / steps) * (PI.toFloat() / 2)
                        val x = arcRadius * cos(angle.toDouble()).toFloat()
                        val y = size.height - arcRadius * sin(angle.toDouble()).toFloat()
                        lineTo(x, y)
                    }
                }

            drawPath(
                path = path,
                color = arcColor,
            )
        }
    }
}

@Composable
private fun PosWindowElement() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 1.dp,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.secondary),
    ) {
        // Window pane dividers
        Column(modifier = Modifier.fillMaxSize()) {
            repeat(2) { index ->
                Box(modifier = Modifier.weight(1f))
                if (index < 1) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.secondary,
                        thickness = 2.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PosCounterElement() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
        tonalElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    ) {
        // Icon overlay
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.TableBar,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f),
            )
        }
    }
}

@Composable
private fun PosDecorationElement() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)),
    ) {
        // Empty - just colored surface
    }
}
