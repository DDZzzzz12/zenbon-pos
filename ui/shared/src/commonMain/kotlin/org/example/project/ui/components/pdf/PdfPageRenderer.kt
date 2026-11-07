package org.example.project.ui.components.pdf

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Platform PDF engine: opens one document and rasterises its pages.
 *
 * Contract: the `%PDF-` magic is sniffed from the first bytes (a MIME type is never trusted) and anything
 * else fails with [PdfLoadError.NotPdf]; empty bytes or zero pages fail with [PdfLoadError.Empty]; a
 * [PdfSource.Url] on a platform that cannot fetch fails with [PdfLoadError.Unsupported]. [render] returns an
 * image of exactly [render]'s `widthPx` and height `round(widthPx * heightPt / widthPt)`, is cancellable,
 * and [close] is idempotent.
 */
interface PdfPageRenderer {
    /** @throws PdfLoadException when the document cannot be opened. */
    suspend fun open(source: PdfSource): PdfDocumentInfo

    suspend fun render(
        pageIndex: Int,
        widthPx: Int,
    ): ImageBitmap

    /** The original document bytes; only valid after a successful [open], otherwise throws [IllegalStateException]. */
    suspend fun readBytes(): ByteArray

    fun close()
}
