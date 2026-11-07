package org.example.project.ui.components.pdf

import kotlin.test.Test
import kotlin.test.assertEquals

class PdfScrollAnchorTest {
    @Test
    fun halfwayThroughAPageStaysHalfwayWhenThePageDoublesInHeight() {
        val anchor = PdfScrollAnchor.capture(pageIndex = 1, offsetPx = 250, pageHeightPx = 500)
        assertEquals(PdfScrollAnchor(pageIndex = 1, fraction = 0.5f), anchor)
        assertEquals(500, anchor.offsetPx(pageHeightPx = 1000))
    }

    @Test
    fun narrowingScalesTheOffsetDown() {
        val anchor = PdfScrollAnchor.capture(pageIndex = 0, offsetPx = 300, pageHeightPx = 1200)
        assertEquals(100, anchor.offsetPx(pageHeightPx = 400))
    }

    @Test
    fun theTopOfThePageStaysAtTheTop() {
        val anchor = PdfScrollAnchor.capture(pageIndex = 0, offsetPx = 0, pageHeightPx = 800)
        assertEquals(0, anchor.offsetPx(pageHeightPx = 2000))
    }

    @Test
    fun aZeroOrNegativeHeightAnchorsToThePageTop() {
        assertEquals(0f, PdfScrollAnchor.capture(pageIndex = 2, offsetPx = 40, pageHeightPx = 0).fraction)
        assertEquals(0f, PdfScrollAnchor.capture(pageIndex = 2, offsetPx = 40, pageHeightPx = -5).fraction)
        assertEquals(0, PdfScrollAnchor(pageIndex = 2, fraction = 0.5f).offsetPx(pageHeightPx = 0))
    }

    /** The list's gap below a page can still count as that page: the fraction never reaches the next page. */
    @Test
    fun theFractionIsClampedBelowOne() {
        val pastTheEnd = PdfScrollAnchor.capture(pageIndex = 0, offsetPx = 510, pageHeightPx = 500)
        assertEquals(true, pastTheEnd.fraction < 1f)
        assertEquals(true, pastTheEnd.offsetPx(pageHeightPx = 1000) < 1000)
        assertEquals(0f, PdfScrollAnchor.capture(pageIndex = 0, offsetPx = -10, pageHeightPx = 500).fraction)
    }

    /** A list scrolled to its end names its last page, which cannot reach the top: keep that page, at its top. */
    @Test
    fun aNamedPageOtherThanTheFirstVisibleIsKeptAtItsTop() {
        val firstVisible = PdfScrollAnchor.capture(pageIndex = 0, offsetPx = 420, pageHeightPx = 530)
        assertEquals(PdfScrollAnchor(pageIndex = 1, fraction = 0f), PdfScrollAnchor.forNamedPage(1, firstVisible))
    }

    @Test
    fun theNamedFirstVisiblePageKeepsItsFraction() {
        val firstVisible = PdfScrollAnchor.capture(pageIndex = 3, offsetPx = 100, pageHeightPx = 400)
        assertEquals(firstVisible, PdfScrollAnchor.forNamedPage(3, firstVisible))
    }

    @Test
    fun aNegativePageIndexIsRejected() {
        assertEquals(0, PdfScrollAnchor.capture(pageIndex = -1, offsetPx = 0, pageHeightPx = 10).pageIndex)
    }
}
