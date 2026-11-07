package org.example.project.ui.components.pdf

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfZoomTest {
    @Test
    fun zoomInMultipliesByStep() {
        assertEquals(1.25f, PdfZoom.zoomIn(1.0f))
    }

    @Test
    fun zoomInStopsAtMax() {
        assertEquals(PdfZoom.MAX, PdfZoom.zoomIn(PdfZoom.MAX))
    }

    @Test
    fun zoomOutStopsAtMin() {
        assertEquals(PdfZoom.MIN, PdfZoom.zoomOut(PdfZoom.MIN))
    }

    @Test
    fun clampLimitsToMax() {
        assertEquals(PdfZoom.MAX, PdfZoom.clamp(10f))
    }

    @Test
    fun renderWidthScalesContainer() {
        assertEquals(3200, PdfZoom.renderWidthPx(800, 4.0f))
    }

    @Test
    fun renderWidthIsClampedToMaxPx() {
        assertEquals(4096, PdfZoom.renderWidthPx(2000, 4.0f))
    }

    @Test
    fun a4AtMaxWidthIsNarrowedBelowThePixelCap() {
        val a4 = PdfPageSize(595f, 842f)
        val width = PdfZoom.pageRenderWidthPx(2000, 4.0f, a4)
        val height = (width * 842f / 595f).roundToInt()
        assertTrue(width.toLong() * height <= PdfZoom.MAX_PAGE_PIXELS, "$width x $height")
        assertEquals(3362, width)
    }

    @Test
    fun landscapePageKeepsTheWidthCap() {
        assertEquals(4096, PdfZoom.pageRenderWidthPx(2000, 4.0f, PdfPageSize(842f, 595f)))
    }

    @Test
    fun smallRenderIsUnchanged() {
        assertEquals(800, PdfZoom.pageRenderWidthPx(800, 1.0f, PdfPageSize(595f, 842f)))
    }

    @Test
    fun longReceiptIsNarrowedToItsArea() {
        // 200 x 5000 pt: 1000 px wide would be 1000 x 25000 = 25M px.
        assertEquals(800, PdfZoom.pageRenderWidthPx(1000, 1.0f, PdfPageSize(200f, 5000f)))
    }

    @Test
    fun extremeAspectNeverGoesBelowOnePixel() {
        assertEquals(1, PdfZoom.pageRenderWidthPx(800, 1.0f, PdfPageSize(1f, 1_000_000_000f), maxPixels = 10))
    }
}
