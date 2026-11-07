package org.example.project.ui.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider

/**
 * Places a [PosDropdown] for a given [PosDropdownAnchor], flipping to the opposite side when the
 * preferred one would run off-window, then clamping so it can never be partly off-screen.
 *
 * Split out of [PosDropdown] and given no Compose dependencies beyond the geometry types so the
 * placement arithmetic — the part with the off-by-one edges — is unit-testable without composing
 * anything. `PosDropdownPositionProviderTest` covers each anchor plus both flip directions.
 *
 * @param gap space between the anchor and the panel, in pixels.
 * @param edgeMargin minimum distance the clamp keeps from the window edges, in pixels, so a menu
 *   opened near the edge does not sit flush against it (NEW-14 / SYS-33). Applied per axis only when
 *   the panel fits with it; a panel too big for the margin still clamps to the edge.
 */
internal class PosDropdownPositionProvider(
    private val anchor: PosDropdownAnchor,
    private val gap: Int,
    private val edgeMargin: Int = 0,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height

        val x =
            when (anchor) {
                PosDropdownAnchor.BottomStart, PosDropdownAnchor.TopStart -> anchorBounds.left
                PosDropdownAnchor.BottomEnd, PosDropdownAnchor.TopEnd -> anchorBounds.right - w
                PosDropdownAnchor.BottomCenter, PosDropdownAnchor.TopCenter ->
                    // Integer division truncates; half a pixel of drift is not worth a rounding rule.
                    anchorBounds.left + (anchorBounds.width - w) / 2
                PosDropdownAnchor.EndTop ->
                    // Flip to the anchor's left when there is no room on the right.
                    if (anchorBounds.right + gap + w <= windowSize.width) {
                        anchorBounds.right + gap
                    } else {
                        anchorBounds.left - gap - w
                    }
                PosDropdownAnchor.StartTop ->
                    if (anchorBounds.left - gap - w >= 0) {
                        anchorBounds.left - gap - w
                    } else {
                        anchorBounds.right + gap
                    }
            }

        val y =
            when (anchor) {
                PosDropdownAnchor.BottomStart, PosDropdownAnchor.BottomEnd, PosDropdownAnchor.BottomCenter ->
                    if (anchorBounds.bottom + gap + h <= windowSize.height) {
                        anchorBounds.bottom + gap
                    } else {
                        anchorBounds.top - gap - h
                    }
                PosDropdownAnchor.TopStart, PosDropdownAnchor.TopEnd, PosDropdownAnchor.TopCenter ->
                    if (anchorBounds.top - gap - h >= 0) {
                        anchorBounds.top - gap - h
                    } else {
                        anchorBounds.bottom + gap
                    }
                PosDropdownAnchor.EndTop, PosDropdownAnchor.StartTop -> anchorBounds.top
            }

        // A flip can still leave a tall panel hanging off the bottom — a rail flyout opened from the
        // last icon is the everyday case — so both axes are clamped afterwards. coerceAtLeast(0)
        // comes last: on a window smaller than the panel, showing the top-left beats showing nothing.
        val mx = if (w + 2 * edgeMargin <= windowSize.width) edgeMargin else 0
        val my = if (h + 2 * edgeMargin <= windowSize.height) edgeMargin else 0
        return IntOffset(
            x.coerceAtMost(windowSize.width - w - mx).coerceAtLeast(mx),
            y.coerceAtMost(windowSize.height - h - my).coerceAtLeast(my),
        )
    }
}
