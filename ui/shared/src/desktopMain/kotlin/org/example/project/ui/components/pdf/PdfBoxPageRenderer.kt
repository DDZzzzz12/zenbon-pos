package org.example.project.ui.components.pdf

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.rendering.RenderDestination
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Desktop [PdfPageRenderer] backed by PDFBox. PDFBox documents and [PDFRenderer] are not thread-safe, so
 * every access runs on [Dispatchers.IO] under [lock], and the document is only ever freed under that lock.
 *
 * [close] never waits for the lock: it is called from the UI thread (`PdfViewerState.dispose`), and a large
 * page can take seconds to rasterise. It marks the renderer [closed] and frees the document at once if the lock
 * is free; otherwise the work holding the lock frees it as it lets go (see [ReleaseOnUnlock]).
 */
internal class PdfBoxPageRenderer(
    /** Test seam: runs inside the lock, after the cancellation check, immediately before a page is rasterised. */
    private val beforeRasterise: () -> Unit = {},
    /** Test seam: runs inside the lock immediately before a document is parsed. */
    private val beforeLoad: () -> Unit = {},
    /** Test seam: runs each time a loaded document is closed. */
    private val onDocumentReleased: () -> Unit = {},
) : PdfPageRenderer {
    private val lock = ReentrantLock()

    /**
     * Set by [close] without the lock and never cleared: a closed renderer refuses to open, render or read again,
     * and whoever holds the lock when it is set frees the document on the way out.
     */
    private val closed = AtomicBoolean(false)
    private val documentLock =
        ReleaseOnUnlock(
            lock = lock::lock,
            tryLock = lock::tryLock,
            unlock = lock::unlock,
            isClosed = { closed.get() },
            release = ::releaseLocked,
        )
    private var document: PDDocument? = null
    private var renderer: PDFRenderer? = null
    private var pages: List<PdfPageSize> = emptyList()
    private var bytes: ByteArray? = null

    override suspend fun open(source: PdfSource): PdfDocumentInfo {
        val data = (source as? PdfSource.Bytes)?.bytes ?: throw PdfLoadException(PdfLoadError.Unsupported)
        return withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            documentLock.withDocumentLock {
                // The lock wait is not cancellable, and close() may have run while this open was queued
                // (PdfViewerState.dispose cancels the open, then closes): never load a document nobody will close.
                context.ensureActive()
                checkOpen("open")
                openLocked(data).also {
                    // close() ran while the document loaded: the lock's release frees it, so do not report it open.
                    checkOpen("open")
                }
            }
        }
    }

    override suspend fun render(
        pageIndex: Int,
        widthPx: Int,
    ): ImageBitmap {
        require(widthPx > 0) { "widthPx must be positive: $widthPx" }
        val image =
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                context.ensureActive()
                documentLock.withDocumentLock {
                    // The wait above is not cancellable: re-check so a render cancelled while queued never rasterises.
                    context.ensureActive()
                    checkOpen("render")
                    beforeRasterise()
                    renderLocked(pageIndex, widthPx)
                }
            }
        return image.toComposeImageBitmap()
    }

    override suspend fun readBytes(): ByteArray =
        withContext(Dispatchers.IO) {
            documentLock.withDocumentLock {
                checkOpen("readBytes")
                checkNotNull(bytes) { "readBytes() called before a successful open()" }
            }
        }

    /** True while another thread waits for the document lock; lets tests queue a render deterministically. */
    internal fun hasQueuedWork(): Boolean = lock.hasQueuedThreads()

    /** Number of threads waiting for the document lock; lets tests order queued calls deterministically. */
    internal fun queuedWorkCount(): Int = lock.queueLength

    /** True while a document is loaded; lets tests prove a refused open left nothing behind. */
    internal fun hasOpenDocument(): Boolean = documentLock.withDocumentLock { document != null }

    /** Returns at once; the document is freed now if the lock is free, else by the work holding it. Idempotent. */
    override fun close() {
        closed.set(true)
        documentLock.releaseIfClosed()
    }

    private fun checkOpen(operation: String) = check(!closed.get()) { "$operation() called on a closed renderer" }

    private fun openLocked(data: ByteArray): PdfDocumentInfo {
        releaseLocked()
        if (data.isEmpty()) throw PdfLoadException(PdfLoadError.Empty)
        if (!PdfRendererSupport.hasPdfMagic(data)) throw PdfLoadException(PdfLoadError.NotPdf)
        beforeLoad()
        val loaded = loadDocument(data)
        val sizes = pageSizesOrClose(loaded)
        document = loaded
        renderer = PDFRenderer(loaded)
        bytes = data
        pages = sizes
        return PdfDocumentInfo(sizes)
    }

    /**
     * Reads every page size; closes [loaded] and throws [PdfLoadException] if the document is empty or malformed.
     * Any other throwable (cancellation, an [Error] such as [OutOfMemoryError]) also closes [loaded] and is rethrown.
     */
    private fun pageSizesOrClose(loaded: PDDocument): List<PdfPageSize> {
        val sizes =
            try {
                loaded.pages.map { page -> displayedSize(page) }
            } catch (e: Throwable) {
                runCatching { loaded.close() }.exceptionOrNull()?.let(e::addSuppressed)
                throw if (e is Exception && e !is CancellationException) PdfLoadException(PdfLoadError.Corrupt, e) else e
            }
        if (sizes.isEmpty()) {
            loaded.close()
            throw PdfLoadException(PdfLoadError.Empty)
        }
        if (sizes.any { !PdfRendererSupport.isRenderable(it) }) {
            loaded.close()
            throw PdfLoadException(PdfLoadError.Corrupt)
        }
        return sizes
    }

    /** Frees the loaded document, if any; only ever called under [lock]. Fields are cleared even if closing throws. */
    private fun releaseLocked() {
        val loaded = document ?: return
        document = null
        renderer = null
        bytes = null
        pages = emptyList()
        try {
            loaded.close()
        } finally {
            onDocumentReleased()
        }
    }

    private fun loadDocument(data: ByteArray): PDDocument =
        try {
            Loader.loadPDF(data)
        } catch (e: InvalidPasswordException) {
            throw PdfLoadException(PdfLoadError.PasswordProtected, e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw PdfLoadException(PdfLoadError.Corrupt, e)
        } catch (e: RuntimeException) {
            throw PdfLoadException(PdfLoadError.Corrupt, e)
        }

    private fun renderLocked(
        pageIndex: Int,
        widthPx: Int,
    ): BufferedImage {
        val pdfRenderer = checkNotNull(renderer) { "render() called without an open document" }
        val size = pages.getOrNull(pageIndex) ?: throw IndexOutOfBoundsException("page $pageIndex of ${pages.size}")
        val heightPx = PdfRendererSupport.renderHeightPx(widthPx, size)
        val image = BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, widthPx, heightPx)
            pdfRenderer.renderPageToGraphics(
                pageIndex,
                graphics,
                widthPx / size.widthPt,
                heightPx / size.heightPt,
                RenderDestination.VIEW,
            )
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun displayedSize(page: org.apache.pdfbox.pdmodel.PDPage): PdfPageSize {
        val box = page.cropBox
        val rotated = page.rotation % 180 != 0
        return if (rotated) PdfPageSize(box.height, box.width) else PdfPageSize(box.width, box.height)
    }
}
