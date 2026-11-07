package org.example.project.ui.components.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Android [PdfPageRenderer] backed by the platform's [PdfRenderer] (PDFium).
 *
 * [PdfRenderer] needs a seekable descriptor, so the bytes are written to a temp file and opened read-only; the
 * file is deleted on [close] and on a failed [open]. The temp file goes to `java.io.tmpdir`, which Android points
 * at the app's cache directory, so no `Context` is needed.
 *
 * [PdfRenderer] allows one open page at a time and throws if closed while a page is open, so every page is opened
 * and closed under one [lock], and the renderer is only ever closed under that same lock — never while a page is
 * open. [close] itself never waits for the lock (it runs on the main thread from `PdfViewerState.dispose`, and
 * waiting out a large render there risks an ANR): it marks the renderer [closed] and frees everything at once if the
 * lock is free; otherwise the open or render holding the lock frees it as it lets go (see [ReleaseOnUnlock]).
 */
internal class AndroidPdfPageRenderer(
    /** Where the temp file is written; `null` means `java.io.tmpdir`. */
    private val tempDir: File? = null,
) : PdfPageRenderer {
    private val lock = ReentrantLock()

    /**
     * Set by [close] without the lock and never cleared: a closed renderer refuses to open, render or read again,
     * and whoever holds the lock when it is set frees the renderer and the temp file on the way out.
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
    private var tempFile: File? = null
    private var renderer: PdfRenderer? = null
    private var pages: List<PdfPageSize> = emptyList()
    private var bytes: ByteArray? = null

    override suspend fun open(source: PdfSource): PdfDocumentInfo {
        // PdfSource.Url is fetched only by the wasm renderer.
        val data = (source as? PdfSource.Bytes)?.bytes ?: throw PdfLoadException(PdfLoadError.Unsupported)
        return withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            documentLock.withDocumentLock {
                // The lock wait is not cancellable, and close() may have run while this open was queued
                // (PdfViewerState.dispose cancels the open, then closes): checked before touching the disk, so a
                // disposed viewer never leaves a live PdfRenderer, descriptor or temp file behind.
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
        val bitmap =
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                context.ensureActive()
                documentLock.withDocumentLock {
                    // The wait above is not cancellable: re-check so a render cancelled while queued never rasterises.
                    context.ensureActive()
                    checkOpen("render")
                    renderLocked(pageIndex, widthPx)
                }
            }
        return bitmap.asImageBitmap()
    }

    override suspend fun readBytes(): ByteArray =
        withContext(Dispatchers.IO) {
            documentLock.withDocumentLock {
                checkOpen("readBytes")
                checkNotNull(bytes) { "readBytes() called before a successful open()" }
            }
        }

    /** Returns at once; everything is freed now if the lock is free, else by the work holding it. Idempotent. */
    override fun close() {
        closed.set(true)
        documentLock.releaseIfClosed()
    }

    private fun checkOpen(operation: String) = check(!closed.get()) { "$operation() called on a closed renderer" }

    private fun openLocked(data: ByteArray): PdfDocumentInfo {
        releaseLocked()
        if (data.isEmpty()) throw PdfLoadException(PdfLoadError.Empty)
        if (!PdfRendererSupport.hasPdfMagic(data)) throw PdfLoadException(PdfLoadError.NotPdf)
        val file = writeTempFile(data)
        try {
            val opened = openRenderer(file)
            val sizes = pageSizesOrClose(opened)
            tempFile = file
            renderer = opened
            pages = sizes
            bytes = data
            return PdfDocumentInfo(sizes)
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    private fun writeTempFile(data: ByteArray): File =
        try {
            File.createTempFile("pdf-viewer-", ".pdf", tempDir).also { it.writeBytes(data) }
        } catch (e: IOException) {
            throw PdfLoadException(PdfLoadError.Corrupt, e)
        }

    /** Opens [file]; the descriptor is owned by the returned renderer, and closed here if the renderer fails. */
    private fun openRenderer(file: File): PdfRenderer {
        val descriptor =
            try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: IOException) {
                throw PdfLoadException(PdfLoadError.Corrupt, e)
            }
        return try {
            PdfRenderer(descriptor)
        } catch (e: Throwable) {
            // PdfRenderer's constructor does not close the descriptor it failed on.
            runCatching { descriptor.close() }
            throw mapOpenFailure(e)
        }
    }

    private fun mapOpenFailure(e: Throwable): Throwable =
        when (e) {
            is CancellationException -> e
            // PDFium reports a document that needs a password as a SecurityException.
            is SecurityException -> PdfLoadException(PdfLoadError.PasswordProtected, e)
            is IOException, is RuntimeException -> PdfLoadException(PdfLoadError.Corrupt, e)
            else -> e
        }

    /**
     * Reads every page size; closes [opened] and throws [PdfLoadException] if the document is empty or malformed.
     * Any other throwable (cancellation, an [Error] such as [OutOfMemoryError]) also closes [opened] and is rethrown.
     * [pageSize] closes its page in a `finally`, so no page is open when [opened] is closed here.
     */
    private fun pageSizesOrClose(opened: PdfRenderer): List<PdfPageSize> {
        val sizes =
            try {
                (0 until opened.pageCount).map { index -> pageSize(opened, index) }
            } catch (e: Throwable) {
                runCatching { opened.close() }.exceptionOrNull()?.let(e::addSuppressed)
                throw if (e is Exception && e !is CancellationException) PdfLoadException(PdfLoadError.Corrupt, e) else e
            }
        if (sizes.isEmpty()) {
            opened.close()
            throw PdfLoadException(PdfLoadError.Empty)
        }
        if (sizes.any { !PdfRendererSupport.isRenderable(it) }) {
            opened.close()
            throw PdfLoadException(PdfLoadError.Corrupt)
        }
        return sizes
    }

    /** [PdfRenderer.Page] sizes are in points and already account for the page's /Rotate. */
    private fun pageSize(
        opened: PdfRenderer,
        index: Int,
    ): PdfPageSize {
        val page = opened.openPage(index)
        try {
            return PdfPageSize(page.width.toFloat(), page.height.toFloat())
        } finally {
            page.close()
        }
    }

    private fun renderLocked(
        pageIndex: Int,
        widthPx: Int,
    ): Bitmap {
        val pdfRenderer = checkNotNull(renderer) { "render() called without an open document" }
        val size = pages.getOrNull(pageIndex) ?: throw IndexOutOfBoundsException("page $pageIndex of ${pages.size}")
        val heightPx = PdfRendererSupport.renderHeightPx(widthPx, size)
        PdfRendererSupport.requireRenderablePixelArea(widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val page = pdfRenderer.openPage(pageIndex)
            try {
                // No clip and no transform: PdfRenderer scales the page to fill the whole bitmap.
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                page.close()
            }
        } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        }
        return bitmap
    }

    /**
     * Frees the renderer (and its descriptor) and deletes the temp file; only ever called under [lock], where no page
     * is open. Idempotent. The fields are cleared first and the file is deleted in a `finally`, so a throwing
     * [PdfRenderer.close] neither leaves the file behind nor lets a later call close the renderer twice.
     */
    private fun releaseLocked() {
        val opened = renderer
        val file = tempFile
        renderer = null
        tempFile = null
        pages = emptyList()
        bytes = null
        try {
            opened?.close()
        } finally {
            file?.delete()
        }
    }
}
