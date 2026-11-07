package org.example.project.ui.components.floorplan

import androidx.compose.ui.geometry.Offset
import org.example.project.core.utils.GridCellDimensions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Pure zoom/pan maths shared by every way of changing the viewport (buttons, wheel, fit). */
object FloorPlanViewport {
    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 5f
    const val STEP = 1.25f

    /** Fraction of the pane the fitted content may fill, so it does not touch the pane edge. */
    private const val FIT_FILL = 0.95f

    fun zoomedBy(
        zoom: Float,
        factor: Float,
        minZoom: Float = MIN_ZOOM,
    ): Float = (zoom * factor).coerceIn(minZoom, MAX_ZOOM)

    /**
     * Pan is bounded to the plan: at zoom z the scaled plan overhangs the pane by (z-1)/2 each side.
     * Below 1x there is no overhang (the bound is 0, never negative, which would make `coerceIn`
     * throw); [overscroll] adds that fraction of the pane on each side, so an editor can pull the
     * plan off-centre. The default 0 keeps the till's behaviour exactly.
     */
    fun clampPan(
        pan: Offset,
        zoom: Float,
        paneWidth: Float,
        paneHeight: Float,
        overscroll: Float = 0f,
    ): Offset {
        val maxX = max((zoom - 1f) * paneWidth / 2f, 0f) + overscroll * paneWidth
        val maxY = max((zoom - 1f) * paneHeight / 2f, 0f) + overscroll * paneHeight
        // `0f - max`, not `-max`: a zero bound must stay +0f, or a clamped pan compares unequal to Offset.Zero.
        return Offset(pan.x.coerceIn(0f - maxX, maxX), pan.y.coerceIn(0f - maxY, maxY))
    }

    /**
     * The pan after the pointer moved by [panChange].
     *
     * [panChange] is in **screen** pixels (the gesture node sits outside the scaled layer) and the layer's
     * translation is also in screen pixels, so the plan follows the pointer one to one at every zoom. Scaling
     * the change by the zoom made the plan run away from the pointer by that factor when zoomed in.
     */
    fun pannedBy(
        pan: Offset,
        panChange: Offset,
        zoom: Float,
        paneWidth: Float,
        paneHeight: Float,
        overscroll: Float = 0f,
    ): Offset = clampPan(pan + panChange, zoom, paneWidth, paneHeight, overscroll)

    /** A zoom and pan, as held by the viewport owner. */
    data class View(
        val zoom: Float,
        val pan: Offset,
    )

    /**
     * The view that shows all of [bounds] (cell units). The plan is `80 x 55` cells of at least
     * 10 px, so on a pane smaller than 800 x 550 px even the bare grid overflows; fitting it zooms
     * out until it is whole again.
     *
     * Zoom is the largest that fits both axes, capped at [maxZoom] (default 1x, the till's rule; the
     * editor passes more so a small plan is framed rather than left small), then clamped to
     * `[minZoom, MAX_ZOOM]`.
     * Bounds beyond the grid keep a 5 % margin; the grid itself does not, so a pane that fits it
     * exactly yields `View(1f, Offset.Zero)` (the till's default). An unmeasured pane also does.
     *
     * Maths: the surface draws plan pixel `p` at screen `c + z*(p - c) + t` (`c` = pane centre,
     * transform origin Center, translation applied after the scale). Putting the bounds' centre
     * `m` (plan pixels) on `c` gives `t = z*(c - m)`.
     */
    fun fit(
        bounds: FloorPlanContentBounds,
        paneWidth: Float,
        paneHeight: Float,
        cells: GridCellDimensions,
        minZoom: Float = MIN_ZOOM,
        maxZoom: Float = 1f,
    ): View {
        val home = View(1f, Offset.Zero)
        if (paneWidth <= 0f || paneHeight <= 0f || cells.cellWidth <= 0f || cells.cellHeight <= 0f) return home

        val fill = if (bounds == FloorPlanContentBounds.GRID) 1f else FIT_FILL
        val contentW = bounds.width * cells.cellWidth
        val contentH = bounds.height * cells.cellHeight
        if (contentW <= 0f || contentH <= 0f) return home
        val raw = min(maxZoom, min(paneWidth * fill / contentW, paneHeight * fill / contentH))
        // Float noise: pane / (80 * (pane / 80)) can be 0.99999994, which is "fits exactly".
        val zoom = (if (raw > NEAR_ONE && raw < 1f) 1f else raw).coerceIn(minZoom, MAX_ZOOM)

        val centreX = (bounds.left + bounds.right) / 2f * cells.cellWidth
        val centreY = (bounds.top + bounds.bottom) / 2f * cells.cellHeight
        return View(zoom, Offset(snapToZero(zoom * (paneWidth / 2f - centreX)), snapToZero(zoom * (paneHeight / 2f - centreY))))
    }

    private const val NEAR_ONE = 0.9999f

    /**
     * Whether a held zoom/pan already IS [view], within float noise (a pan that went through a clamp
     * or a pointer round trip can differ in the last bit). Drives "Fit" being enabled.
     */
    fun isSameView(
        zoom: Float,
        pan: Offset,
        view: View,
    ): Boolean = abs(zoom - view.zoom) < 0.001f && abs(pan.x - view.pan.x) < 0.5f && abs(pan.y - view.pan.y) < 0.5f

    /** Sub-half-pixel pan is rounding noise (and `-0f` would compare unequal to `Offset.Zero`). */
    private fun snapToZero(v: Float): Float = if (abs(v) < 0.5f) 0f else v

    /** Keeps the point under the pane's centre fixed while the zoom changes. */
    fun panAfterZoom(
        pan: Offset,
        oldZoom: Float,
        newZoom: Float,
        paneWidth: Float,
        paneHeight: Float,
        overscroll: Float = 0f,
    ): Offset {
        val scaled = if (oldZoom > 0f) pan * (newZoom / oldZoom) else Offset.Zero
        return clampPan(scaled, newZoom, paneWidth, paneHeight, overscroll)
    }

    /** One wheel notch = one step; wheel-up (negative deltaY) zooms in. Zero delta is no change. */
    fun wheelFactor(deltaY: Float): Float =
        when {
            deltaY < 0f -> STEP
            deltaY > 0f -> 1f / STEP
            else -> 1f
        }
}
