package org.example.project.ui.components.pdf

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

object PdfZoom {
    const val MIN = 0.5f
    const val MAX = 4.0f
    const val STEP = 1.25f
    const val FIT_WIDTH = 1.0f

    /**
     * Largest bitmap area a page is rendered at. iOS Safari refuses canvases above ~16.7M pixels (the wasm
     * renderer draws onto one), and the cap also bounds a single page's memory to 64 MB on every platform.
     */
    const val MAX_PAGE_PIXELS = 16_000_000L

    fun zoomIn(current: Float): Float = clamp(current * STEP)

    fun zoomOut(current: Float): Float = clamp(current / STEP)

    fun clamp(scale: Float): Float = scale.coerceIn(MIN, MAX)

    fun renderWidthPx(
        containerWidthPx: Int,
        scale: Float,
        maxPx: Int = 4096,
    ): Int = (containerWidthPx * scale).roundToInt().coerceAtMost(maxPx)

    /** [renderWidthPx] for one page, narrowed so the bitmap stays within [maxPixels] for the page's aspect ratio. */
    fun pageRenderWidthPx(
        containerWidthPx: Int,
        scale: Float,
        page: PdfPageSize,
        maxPixels: Long = MAX_PAGE_PIXELS,
    ): Int = renderWidthPx(containerWidthPx, scale).coerceAtMost(maxWidthForArea(page, maxPixels)).coerceAtLeast(1)

    /** Widest render of [page] whose `width * round(width * heightPt / widthPt)` does not exceed [maxPixels]. */
    fun maxWidthForArea(
        page: PdfPageSize,
        maxPixels: Long,
    ): Int {
        val aspect = page.heightPt / page.widthPt
        var width = floor(sqrt(maxPixels / aspect.toDouble())).toInt().coerceAtLeast(1)
        while (width > 1 && width.toLong() * heightFor(width, aspect) > maxPixels) width--
        return width
    }

    private fun heightFor(
        width: Int,
        aspect: Float,
    ): Int = (width * aspect).roundToInt()
}
