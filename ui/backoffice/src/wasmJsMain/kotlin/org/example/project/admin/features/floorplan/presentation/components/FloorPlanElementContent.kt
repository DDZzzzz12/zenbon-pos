package org.example.project.admin.features.floorplan.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.models.RoomElement
import org.example.project.core.presentation.strings.str

/** Label size as a fraction of one grid cell: the design prints 11px on a 24px cell. */
private const val LABEL_PER_CELL = 11f / 24f

/**
 * A room element as the design draws it, instead of the till's tonal tiles: a hatched wall, a white door with
 * a solid border and the word "Tür", a window with a double border, and the counter as a flat grey block
 * labelled "Theke". Everything is neutral (no status colour), square-cornered and drawn with 1–2 screen
 * pixels of border: the plan is enlarged by a zoom layer, so every width is given as `n dp / zoom`.
 *
 * @param zoom the plan's current zoom.
 */
@Composable
fun FloorPlanElementContent(
    element: RoomElement,
    zoom: Float,
) {
    val screenPx = 1.dp / zoom.coerceAtLeast(MIN_ZOOM)
    val fill = MaterialTheme.colorScheme.surfaceContainerHighest
    val line = MaterialTheme.colorScheme.outline
    val strong = MaterialTheme.colorScheme.onSurfaceVariant
    val hatch = MaterialTheme.colorScheme.onSurface.copy(alpha = HATCH_ALPHA)
    val white = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cell = minOf(maxWidth / element.gridWidth.coerceAtLeast(1), maxHeight / element.gridHeight.coerceAtLeast(1))
        Canvas(Modifier.fillMaxSize()) {
            val one = with(density) { screenPx.toPx() }
            when (element.elementType) {
                "WALL" -> {
                    drawRect(fill)
                    clipRect { drawHatch(hatch, spacing = 6f * one, width = one) }
                    drawBorder(line, one)
                }
                "DOOR" -> {
                    drawRect(white)
                    drawBorder(strong, 2f * one)
                }
                "WINDOW" -> {
                    drawRect(white)
                    // CSS "2px double": two thin lines with a gap of the same width.
                    drawBorder(strong, one * 2f / 3f)
                    drawBorder(strong, one * 2f / 3f, inset = one * 4f / 3f)
                }
                "COUNTER" -> {
                    drawRect(fill)
                    drawBorder(line, one)
                }
                else -> {
                    drawRect(fill)
                    drawBorder(line, one)
                }
            }
        }
        val label =
            when (element.elementType) {
                "DOOR" -> str(AdminStringKeys.FloorPlanElementDoor)
                "COUNTER" -> str(AdminStringKeys.FloorPlanElementCounter)
                else -> null
            }
        if (label != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = label,
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontSize = with(density) { (cell * LABEL_PER_CELL).coerceAtLeast(MIN_LABEL).toSp() },
                            lineHeight = with(density) { (cell * LABEL_PER_CELL * LABEL_LINE).coerceAtLeast(MIN_LABEL).toSp() },
                        ),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

private const val MIN_ZOOM = 0.05f
private const val HATCH_ALPHA = 0.16f
private const val LABEL_LINE = 14f / 11f
private val MIN_LABEL = 2.dp

private fun DrawScope.drawBorder(
    color: Color,
    width: Float,
    inset: Float = 0f,
) {
    val half = width / 2f
    drawRect(
        color = color,
        topLeft = Offset(inset + half, inset + half),
        size = Size(size.width - 2 * (inset + half), size.height - 2 * (inset + half)),
        style = Stroke(width = width),
    )
}

/** Diagonal stripes running bottom-left to top-right (a 135° repeating gradient), [spacing] apart. */
private fun DrawScope.drawHatch(
    color: Color,
    spacing: Float,
    width: Float,
) {
    if (spacing <= 0f) return
    var x = -size.height
    while (x < size.width) {
        drawLine(color, start = Offset(x, size.height), end = Offset(x + size.height, 0f), strokeWidth = width)
        x += spacing
    }
}
