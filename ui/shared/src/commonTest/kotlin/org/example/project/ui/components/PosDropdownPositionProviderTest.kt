package org.example.project.ui.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Placement is the part of a popup that goes wrong invisibly: a panel that opens off-window looks
 * like a control that did nothing. These pin each anchor, both flip directions and the clamp.
 */
class PosDropdownPositionProviderTest {
    private val window = IntSize(1000, 800)

    /** A 40x40 control at (100, 100) — a rail icon or a small button. */
    private val control = IntRect(left = 100, top = 100, right = 140, bottom = 140)

    private fun place(
        anchor: PosDropdownAnchor,
        popup: IntSize,
        bounds: IntRect = control,
        gap: Int = 4,
    ): IntOffset =
        PosDropdownPositionProvider(anchor, gap)
            .calculatePosition(bounds, window, LayoutDirection.Ltr, popup)

    @Test
    fun bottomStartHangsBelowWithLeftEdgesFlush() {
        assertEquals(IntOffset(100, 144), place(PosDropdownAnchor.BottomStart, IntSize(200, 120)))
    }

    @Test
    fun bottomEndHangsBelowWithRightEdgesFlush() {
        // Deliberately NOT the control at x=100: 140 - 200 is negative there, so the clamp would
        // rescue it to 0 and the test would pass while proving nothing about right-edge alignment.
        val midWindow = IntRect(left = 500, top = 100, right = 540, bottom = 140)
        assertEquals(IntOffset(340, 144), place(PosDropdownAnchor.BottomEnd, IntSize(200, 120), midWindow))
    }

    @Test
    fun aRightAlignedPanelWiderThanItsControlIsClampedAtTheLeftEdge() {
        // The case the previous test used to cover by accident, named for what it actually is.
        assertEquals(IntOffset(0, 144), place(PosDropdownAnchor.BottomEnd, IntSize(200, 120)))
    }

    @Test
    fun endTopSitsToTheRightWithTopEdgesFlush() {
        assertEquals(IntOffset(144, 100), place(PosDropdownAnchor.EndTop, IntSize(200, 120)))
    }

    @Test
    fun endTopFlipsToTheLeftWhenTheRightEdgeIsCrowded() {
        val nearRight = IntRect(left = 900, top = 100, right = 940, bottom = 140)
        // 940 + 4 + 200 overflows 1000, so it opens leftward: 900 - 4 - 200.
        assertEquals(IntOffset(696, 100), place(PosDropdownAnchor.EndTop, IntSize(200, 120), nearRight))
    }

    @Test
    fun bottomStartFlipsUpwardWhenTheBottomIsCrowded() {
        val nearBottom = IntRect(left = 100, top = 700, right = 140, bottom = 740)
        // 740 + 4 + 120 overflows 800, so it opens upward: 700 - 4 - 120.
        assertEquals(IntOffset(100, 576), place(PosDropdownAnchor.BottomStart, IntSize(200, 120), nearBottom))
    }

    @Test
    fun topStartFlipsDownwardWhenThereIsNoRoomAbove() {
        val nearTop = IntRect(left = 100, top = 10, right = 140, bottom = 50)
        // 10 - 4 - 120 is negative, so it opens downward instead.
        assertEquals(IntOffset(100, 54), place(PosDropdownAnchor.TopStart, IntSize(200, 120), nearTop))
    }

    @Test
    fun aTallPanelIsClampedIntoTheWindowRatherThanRunningOffTheBottom() {
        // The everyday rail case: EndTop pins y to the anchor's top, which for a low icon and a tall
        // panel would end below the window. Flipping does not apply on this axis, so the clamp does.
        val lowIcon = IntRect(left = 20, top = 600, right = 60, bottom = 640)
        val offset = place(PosDropdownAnchor.EndTop, IntSize(200, 400), lowIcon)
        assertEquals(400, offset.y)
        assertTrue(offset.y + 400 <= window.height, "panel must end inside the window")
    }

    @Test
    fun aPanelLargerThanTheWindowShowsItsTopLeftRatherThanNothing() {
        val offset = place(PosDropdownAnchor.BottomStart, IntSize(1200, 900))
        assertEquals(IntOffset(0, 0), offset)
    }

    @Test
    fun topCenterSitsAboveWithTheCentresOnOneVerticalLine() {
        // The bottom-bar case, so the control is low in the window: 600 - 4 gap - 120 tall = 476.
        // Its centre is x=120, and a 200-wide panel spanning 20..220 has the same centre.
        val barItem = IntRect(left = 100, top = 600, right = 140, bottom = 640)
        val offset = place(PosDropdownAnchor.TopCenter, IntSize(200, 120), barItem)
        assertEquals(IntOffset(20, 476), offset)
        assertEquals(barItem.left + barItem.width / 2, offset.x + 200 / 2, "centres must line up")
    }

    @Test
    fun topCenterFlipsBelowWhenThereIsNoRoomAbove() {
        val nearTop = IntRect(left = 100, top = 10, right = 140, bottom = 50)
        val offset = place(PosDropdownAnchor.TopCenter, IntSize(200, 120), nearTop)
        assertEquals(54, offset.y, "flipped to hang below the control")
        assertEquals(nearTop.left + nearTop.width / 2, offset.x + 200 / 2, "still centred after the flip")
    }

    @Test
    fun bottomCenterHangsBelowWithTheCentresOnOneVerticalLine() {
        val offset = place(PosDropdownAnchor.BottomCenter, IntSize(200, 120))
        assertEquals(144, offset.y)
        assertEquals(control.left + control.width / 2, offset.x + 200 / 2, "centres must line up")
    }

    @Test
    fun aCentredPanelWiderThanTheWindowEdgeIsClampedRatherThanCentred() {
        // A bar item at the far left: centring would place the panel at a negative x. The clamp wins,
        // so the panel is fully visible and merely off-centre.
        val leftEdge = IntRect(left = 0, top = 700, right = 120, bottom = 760)
        val offset = place(PosDropdownAnchor.TopCenter, IntSize(300, 120), leftEdge)
        assertEquals(0, offset.x)
    }

    @Test
    fun anEdgeMarginKeepsAClampedPanelOffTheWindowEdge() {
        // NEW-14: a BottomStart menu opened from a control near the right edge used to sit flush
        // against the window edge; with an 8 px margin it stops 8 px short of it.
        val nearRight = IntRect(left = window.width - 60, top = 100, right = window.width - 20, bottom = 140)
        val offset =
            PosDropdownPositionProvider(PosDropdownAnchor.BottomStart, gap = 4, edgeMargin = 8)
                .calculatePosition(nearRight, window, LayoutDirection.Ltr, IntSize(200, 120))
        assertEquals(window.width - 200 - 8, offset.x)
        // A panel that cannot fit with the margins drops them and clamps flush to the edge instead.
        val tooWide =
            PosDropdownPositionProvider(PosDropdownAnchor.BottomStart, gap = 4, edgeMargin = 8)
                .calculatePosition(nearRight, window, LayoutDirection.Ltr, IntSize(window.width - 4, 120))
        assertEquals(4, tooWide.x)
    }
}
