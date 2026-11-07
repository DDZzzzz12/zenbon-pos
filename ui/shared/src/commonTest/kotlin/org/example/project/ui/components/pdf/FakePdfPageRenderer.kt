package org.example.project.ui.components.pdf

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.roundToInt

/**
 * Scripted [PdfPageRenderer] for state tests: [openFailure] decides the open outcome, [openGate] and [renderGate]
 * (when set) hold the open and every render until completed, and the counters record what the viewer asked for.
 */
class FakePdfPageRenderer(
    private val pages: List<PdfPageSize> = List(3) { PdfPageSize(595f, 842f) },
    private val openFailure: Throwable? = null,
    private val bytes: ByteArray = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D),
    private val readFailure: Throwable? = null,
) : PdfPageRenderer {
    var openCalls = 0
    var closeCalls = 0
    val renderRequests = mutableListOf<Pair<Int, Int>>()
    val completedRenders = mutableListOf<Pair<Int, Int>>()
    var renderGate: CompletableDeferred<Unit>? = null
    var openGate: CompletableDeferred<Unit>? = null

    override suspend fun open(source: PdfSource): PdfDocumentInfo {
        openCalls++
        openGate?.await()
        openFailure?.let { throw it }
        return PdfDocumentInfo(pages)
    }

    override suspend fun render(
        pageIndex: Int,
        widthPx: Int,
    ): ImageBitmap {
        renderRequests += pageIndex to widthPx
        renderGate?.await()
        completedRenders += pageIndex to widthPx
        val size = pages[pageIndex]
        return FakeImageBitmap(widthPx, (widthPx * size.heightPt / size.widthPt).roundToInt())
    }

    override suspend fun readBytes(): ByteArray {
        readFailure?.let { throw it }
        return bytes
    }

    override fun close() {
        closeCalls++
    }
}
