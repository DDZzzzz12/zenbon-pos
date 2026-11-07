package org.example.project.admin.features.documents.presentation.scanner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.example.project.admin.features.documents.presentation.scanner.imaging.Pt
import org.example.project.admin.features.documents.presentation.scanner.imaging.Quad
import org.example.project.admin.features.documents.presentation.scanner.imaging.QuadMath
import kotlin.math.roundToInt

/**
 * The captured frame with four draggable corners. Hit radius is 48dp regardless of how big the
 * handles are drawn; while a corner is held, a 2x loupe floats above the finger so the fingertip
 * does not hide what is being aligned. All geometry goes through [QuadMath] (unit-tested).
 */
@Composable
fun QuadEditor(
    image: ImageBitmap,
    quad: Quad,
    onMoveCorner: (index: Int, imagePoint: Pt) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val hitRadiusPx = with(density) { 48.dp.toPx() }
    val handleRadiusPx = with(density) { 11.dp.toPx() }
    val loupeSizePx = with(density) { 96.dp.toPx() }
    val primary = MaterialTheme.colorScheme.primary
    var activeCorner by remember { mutableStateOf<Int?>(null) }
    var fingerView by remember { mutableStateOf(Offset.Zero) }
    // Finger position minus the corner it grabbed, so the corner keeps its distance from the finger instead of jumping to it.
    var grabOffset by remember { mutableStateOf(Offset.Zero) }

    // Latest values for the long-lived pointer coroutine.
    val currentQuad by rememberUpdatedState(quad)
    val currentOnMoveCorner by rememberUpdatedState(onMoveCorner)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    Box(modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(image) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val fit = QuadMath.fit(image.width.toFloat(), image.height.toFloat(), size.width.toFloat(), size.height.toFloat())
                            val corners = currentQuad.toList().map { QuadMath.toView(it, fit) }
                            val viewQuad = Quad(corners[0], corners[1], corners[2], corners[3])
                            val grabbed = QuadMath.nearestCorner(viewQuad, Pt(start.x, start.y), hitRadiusPx)
                            activeCorner = grabbed
                            grabOffset = grabbed?.let { Offset(start.x - corners[it].x, start.y - corners[it].y) } ?: Offset.Zero
                            fingerView = start
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val index = activeCorner ?: return@detectDragGestures
                            val fit = QuadMath.fit(image.width.toFloat(), image.height.toFloat(), size.width.toFloat(), size.height.toFloat())
                            fingerView = change.position
                            currentOnMoveCorner(index, QuadMath.toImage(Pt(change.position.x - grabOffset.x, change.position.y - grabOffset.y), fit))
                        },
                        onDragEnd = {
                            activeCorner = null
                            currentOnDragEnd()
                        },
                        onDragCancel = {
                            activeCorner = null
                            currentOnDragEnd()
                        },
                    )
                },
        ) {
            val fit = QuadMath.fit(image.width.toFloat(), image.height.toFloat(), size.width, size.height)
            drawImage(
                image,
                dstOffset = IntOffset(fit.offsetX.roundToInt(), fit.offsetY.roundToInt()),
                dstSize = IntSize((image.width * fit.scale).roundToInt(), (image.height * fit.scale).roundToInt()),
            )
            val pts = currentQuad.toList().map { QuadMath.toView(it, fit) }
            val outline =
                Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1..3) lineTo(pts[i].x, pts[i].y)
                    close()
                }
            drawPath(outline, primary.copy(alpha = 0.18f))
            drawPath(outline, primary, style = Stroke(width = 3f))
            pts.forEachIndexed { i, p ->
                drawCircle(Color.White, handleRadiusPx + 3f, Offset(p.x, p.y))
                drawCircle(if (i == activeCorner) Color(0xFFFFB300) else primary, handleRadiusPx, Offset(p.x, p.y))
            }
            val held = activeCorner
            if (held != null) {
                // Loupe: 2x crop of the source around the held corner, drawn above the finger.
                val c = currentQuad[held]
                // Shift the source window to stay inside the image rather than shrinking it, so dst stays square.
                val srcSize = (loupeSizePx / 2f / fit.scale).roundToInt().coerceIn(1, minOf(image.width, image.height))
                val srcLeft = (c.x - srcSize / 2f).roundToInt().coerceIn(0, image.width - srcSize)
                val srcTop = (c.y - srcSize / 2f).roundToInt().coerceIn(0, image.height - srcSize)
                val lx = (fingerView.x - loupeSizePx / 2f).coerceIn(0f, (size.width - loupeSizePx).coerceAtLeast(0f))
                val ly = (fingerView.y - loupeSizePx * 1.6f).coerceAtLeast(0f)
                drawImage(
                    image,
                    srcOffset = IntOffset(srcLeft, srcTop),
                    srcSize = IntSize(srcSize, srcSize),
                    dstOffset = IntOffset(lx.roundToInt(), ly.roundToInt()),
                    dstSize = IntSize(loupeSizePx.roundToInt(), loupeSizePx.roundToInt()),
                )
                drawRect(Color.White, Offset(lx, ly), Size(loupeSizePx, loupeSizePx), style = Stroke(width = 3f))
                drawCircle(primary, 5f, Offset(lx + loupeSizePx / 2f, ly + loupeSizePx / 2f))
            }
        }
    }
}
