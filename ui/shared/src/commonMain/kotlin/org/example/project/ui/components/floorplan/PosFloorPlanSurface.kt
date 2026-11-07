package org.example.project.ui.components.floorplan

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import org.example.project.core.utils.GridCellDimensions
import org.example.project.core.utils.GridConverter

/**
 * The floor plan's geometry host: one place that decides how big a cell is, where the plan's edges
 * are, and how pan/zoom is applied.
 *
 * Cell dimensions come from [GridConverter.calculateCellDimensions] over the surface's own
 * constraints — the same call the till has always made — so all 80 × 55 cells fill the surface at
 * 100 % zoom and both apps compute identical geometry for a given pane. The grid and every child
 * live inside a single [graphicsLayer], so there is no second coordinate space to drift.
 *
 * Viewport state is hoisted: the caller owns [zoom]/[pan] and receives clamped values back through
 * [onTransform]. Pan is bounded to the plan, so neither app can scroll into empty space.
 *
 * The surface accumulates the gesture internally and renders from that, treating [zoom]/[pan] as
 * the seed and as the channel for host-driven changes — a viewport reset, or restored state. It
 * cannot render from the parameters directly: a pointer-input handler's captures are frozen for its
 * lifetime, and the host's value only returns a frame later. See the comment at the accumulation
 * site.
 *
 * @param zoomRange Clamp for zoom. The default floor of 1× is deliberate — at 1× the whole plan
 *   already fits, so zooming out would only add empty margin.
 * @param panOverscroll Extra pan, as a fraction of the pane per side, beyond the plan's own overhang
 *   (see [FloorPlanViewport.clampPan]). The default 0 is the till's behaviour; the editor lets the
 *   plan be pulled off-centre.
 * @param onViewportChanged Fired when the pane is measured or resized, with both the pane size and
 *   the derived cells. Hosts that need this for their own hit-testing (the till's combine mode)
 *   mirror it into their state here. The pane size is reported rather than reconstructed from the
 *   cells because [GridConverter.calculateCellDimensions] clamps cell size to 10..100 px, so
 *   `cellWidth * GRID_COLUMNS` is not the pane width once that clamp bites.
 */
@Composable
fun PosFloorPlanSurface(
    zoom: Float,
    pan: Offset,
    onTransform: (zoom: Float, pan: Offset) -> Unit,
    modifier: Modifier = Modifier,
    zoomRange: ClosedFloatingPointRange<Float> = 1f..5f,
    panOverscroll: Float = 0f,
    gridStyle: FloorPlanGridStyle = FloorPlanGridStyle.Dots,
    onBackgroundTap: () -> Unit = {},
    onViewportChanged: (paneWidth: Float, paneHeight: Float, cells: GridCellDimensions) -> Unit = { _, _, _ -> },
    content: @Composable FloorPlanScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val paneWidth = constraints.maxWidth.toFloat()
        val paneHeight = constraints.maxHeight.toFloat()
        val cells = GridConverter.calculateCellDimensions(paneWidth, paneHeight)

        val latestOnViewportChanged by rememberUpdatedState(onViewportChanged)
        // The tap detector is keyed on Unit, so it captures its lambda once and keeps that instance
        // for the life of the node. A caller passing an inline lambda would be calling the instance
        // from the first composition forever; reading it through rememberUpdatedState leaves the
        // detector running while still invoking the current lambda.
        val latestOnBackgroundTap by rememberUpdatedState(onBackgroundTap)
        val latestOnTransform by rememberUpdatedState(onTransform)
        LaunchedEffect(paneWidth, paneHeight, cells) {
            latestOnViewportChanged(paneWidth, paneHeight, cells)
        }

        // Gesture state is held as snapshot state rather than read from the parameters, for two
        // reasons.
        //
        // Correctness: a pointerInput block's captured values are frozen for the life of the
        // handler. Compose deliberately does not restart it when recomposition produces a new
        // lambda instance from the same call site — it compares handler *classes*, not instances
        // (SuspendingPointerInputFilter.kt: "Lambda literals will have a new instance every time").
        // A plain captured `zoom`/`pan` would therefore stay at its first-composition value, so a
        // pinch could not accumulate: every event would re-base on the same frozen number. Reading
        // MutableState is a snapshot read, which is always current inside the running coroutine.
        //
        // Latency: reporting outward and waiting for the host to feed the value back loses every
        // pointer event that lands inside the same frame, so the gesture would crawl even with
        // rememberUpdatedState.
        // The seed is clamped, not just the gesture. Otherwise a host that hands in a zoom outside
        // zoomRange — 0f from uninitialized or restored state, say — would scale the whole plan to
        // nothing and leave the operator staring at an empty pane until they happened to pinch.
        // Clamping here is what lets the till drop the LaunchedEffect(zoomLevel == 0f) self-heal it
        // used to carry: the invariant now belongs to the component that promises it.
        var liveZoom by remember { mutableStateOf(zoom.coerceIn(zoomRange.start, zoomRange.endInclusive)) }
        var livePan by remember { mutableStateOf(pan) }

        // Adopt host-driven changes (a viewport reset, restored state, a host that clamps
        // differently) without fighting the echo of our own reports: when the host already agrees,
        // this is a no-op. Keying on the parameter matters — the effect must not re-run on every
        // recomposition, or it would drag a live gesture back to the host's lagging value.
        LaunchedEffect(zoom, zoomRange) {
            val clamped = zoom.coerceIn(zoomRange.start, zoomRange.endInclusive)
            if (clamped != liveZoom) liveZoom = clamped
        }
        LaunchedEffect(pan) { if (pan != livePan) livePan = pan }

        val scope = FloorPlanScopeImpl(cells = cells, zoom = liveZoom)

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(zoomRange, paneWidth, paneHeight, panOverscroll) {
                        detectTransformGestures { _, panChange, zoomChange, _ ->
                            val newZoom = (liveZoom * zoomChange).coerceIn(zoomRange.start, zoomRange.endInclusive)
                            // Bounded by FloorPlanViewport.clampPan: the plan's overhang at this zoom
                            // (never negative below 1x) plus the host's overscroll.
                            val newPan =
                                FloorPlanViewport.pannedBy(
                                    pan = livePan,
                                    panChange = panChange,
                                    zoom = newZoom,
                                    paneWidth = paneWidth,
                                    paneHeight = paneHeight,
                                    overscroll = panOverscroll,
                                )
                            liveZoom = newZoom
                            livePan = newPan
                            latestOnTransform(newZoom, newPan)
                        }
                    }.pointerInput(Unit) { detectTapGestures { latestOnBackgroundTap() } },
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        // Render from the gesture state, never the parameters: rendering from a
                        // value that round-trips through the host would lag the gesture by a frame
                        // and let the grid visibly separate from the cells mid-pinch.
                        .graphicsLayer {
                            scaleX = liveZoom
                            scaleY = liveZoom
                            translationX = livePan.x
                            translationY = livePan.y
                            transformOrigin = TransformOrigin.Center
                        },
            ) {
                PosFloorPlanGrid(cells = cells, style = gridStyle, zoom = liveZoom)
                scope.content()
            }
        }
    }
}
