/**
 * Zoom and pan gesture handling modifier for the floor plan canvas.
 *
 * Provides pinch-to-zoom and pan gestures with constrained zoom levels
 * and smooth transformations using graphicsLayer for hardware acceleration.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.dashboard.presentation.components.floorplan

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import kotlin.math.max

/**
 * Applies zoom and pan gesture detection to the canvas.
 *
 * Supports multiple input methods:
 * - Touch: Pinch-to-zoom and drag-to-pan gestures
 * - Mouse: Scroll wheel to zoom, drag to pan
 * - Trackpad: Two-finger scroll to zoom and pan
 *
 * Zoom constraints: 100% (1.0x) minimum to 500% (5.0x) maximum
 * Users can only zoom IN from the default 100% view.
 *
 * Uses hardware-accelerated transforms via graphicsLayer for smooth performance.
 *
 * @param zoom Current zoom level (scale factor)
 * @param panX Current horizontal pan offset in pixels
 * @param panY Current vertical pan offset in pixels
 * @param onZoomChange Callback when zoom level changes (delta value)
 * @param onPanChange Callback when pan offset changes (delta x, delta y)
 * @return Modified Modifier with zoom/pan gesture handling
 */
fun Modifier.zoomPanGestures(
    zoom: Float,
    panX: Float,
    panY: Float,
    viewportSize: IntSize,
    contentSize: IntSize,
    onZoomChange: (Float) -> Unit,
    onPanChange: (Float, Float) -> Unit,
): Modifier =
    this
        .graphicsLayer(
            scaleX = if (zoom < 1f) 1f else zoom,
            scaleY = if (zoom < 1f) 1f else zoom,
            translationX = panX,
            translationY = panY,
        )
        // Mouse scroll wheel zoom support
        .pointerInput(Unit) {
            awaitEachGesture {
                val event = awaitPointerEvent()

                if (event.type == PointerEventType.Scroll) {
                    val scrollDelta = event.changes.first().scrollDelta

                    // Vertical scroll (scrollDelta.y) for zoom
                    // Positive = scroll down = zoom out, Negative = scroll up = zoom in
                    val zoomFactor = if (scrollDelta.y > 0) 0.9f else 1.1f
                    val newZoom = (zoom * zoomFactor).coerceIn(0.9f, 5.0f)
                    val zoomDelta = newZoom - zoom

                    println("zoom: $zoom")
                    println("zoomDelta: $zoomDelta")

                    if (zoomDelta != 0f) {
                        onZoomChange(zoomDelta)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
//    // Mouse drag panning (click and drag to move viewport)
//    .pointerInput(Unit) {
//        detectDragGestures { change, dragAmount ->
//            change.consume()
//
//            // Content boundaries after scaling
//            val scaledContentWidth = contentSize.width * zoom
//            val scaledContentHeight = contentSize.height * zoom
//
//            val maxPanX = max(0f, (scaledContentWidth - viewportSize.width) / 2f)
//            val maxPanY = max(0f, (scaledContentHeight - viewportSize.height) / 2f)
//
//            // Pan update with drag delta (clamped to boundaries)
//            val newPanX = (panX + dragAmount.x).coerceIn(-maxPanX, maxPanX)
//            val newPanY = (panY + dragAmount.y).coerceIn(-maxPanY, maxPanY)
//
//            onPanChange(newPanX, newPanY)
//        }
//    }
        // Touch/trackpad pinch-to-zoom and pan gestures
        .pointerInput(Unit) {
            detectTransformGestures { centroid, pan, zoomChange, rotation ->
                // Update zoom level with constraints (50% to 500%)
                val newZoom = (zoom * zoomChange).coerceIn(0.9f, 5.0f)
                val zoomDelta = newZoom - zoom

                println("zoomChange: $zoomChange")
                println("zoom: $zoom")
                println("zoomDelta: $zoomDelta")

                if (zoomDelta != 0f) {
                    onZoomChange(zoomDelta)
                }

                // Content boundaries after scaling
                val scaledContentWidth = contentSize.width * newZoom
                val scaledContentHeight = contentSize.height * newZoom

                val maxPanX = max(0f, (scaledContentWidth - viewportSize.width) / 2f)
                val maxPanY = max(0f, (scaledContentHeight - viewportSize.height) / 2f)

                // Pan update (clamped)
                val newPanX = (panX + pan.x).coerceIn(-maxPanX, maxPanX)
                val newPanY = (panY + pan.y).coerceIn(-maxPanY, maxPanY)

                onPanChange(newPanX, newPanY)
            }
        }
