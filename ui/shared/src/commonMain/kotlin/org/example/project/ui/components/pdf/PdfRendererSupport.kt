package org.example.project.ui.components.pdf

import kotlin.math.roundToInt

/**
 * Platform-neutral pieces of the [PdfPageRenderer] contract, shared by the byte-array actuals (desktop PDFBox,
 * Android `PdfRenderer`) so both sniff, size and validate a document identically.
 */
internal object PdfRendererSupport {
    /** `%PDF-` may be preceded by up to this many bytes of junk (ISO 32000 allows it; PDFBox tolerates it). */
    const val MAGIC_WINDOW = 1024

    /** Upper bound on one rendered page's pixel count (≈ 256 MB as ARGB_8888); the viewer stays well below it. */
    const val MAX_RENDER_PIXELS = 64L * 1024 * 1024

    private val MAGIC = "%PDF-".encodeToByteArray()

    /** True when `%PDF-` starts within the first [MAGIC_WINDOW] bytes of [data]. */
    fun hasPdfMagic(data: ByteArray): Boolean {
        val limit = minOf(data.size, MAGIC_WINDOW) - MAGIC.size
        return (0..limit).any { start -> MAGIC.indices.all { data[start + it] == MAGIC[it] } }
    }

    /** The contract's render height: `round(widthPx * heightPt / widthPt)`, never below one pixel. */
    fun renderHeightPx(
        widthPx: Int,
        size: PdfPageSize,
    ): Int = (widthPx * size.heightPt / size.widthPt).roundToInt().coerceAtLeast(1)

    /** A page can be rasterised only when both sides are finite and positive. */
    fun isRenderable(size: PdfPageSize): Boolean = size.widthPt.isFinite() && size.heightPt.isFinite() && size.widthPt > 0f && size.heightPt > 0f

    /** @throws IllegalArgumentException when a [widthPx] x [heightPx] bitmap would exceed [MAX_RENDER_PIXELS]. */
    fun requireRenderablePixelArea(
        widthPx: Int,
        heightPx: Int,
    ) {
        val area = widthPx.toLong() * heightPx.toLong()
        require(area <= MAX_RENDER_PIXELS) { "render of ${widthPx}x$heightPx px exceeds $MAX_RENDER_PIXELS pixels" }
    }
}
