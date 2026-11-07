package org.example.project.ui.components.pdf

import androidx.compose.ui.graphics.ImageBitmap
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State and render orchestration behind `PdfViewer`: the one-time open, zoom, the current page, which page
 * bitmaps are shown, and the download.
 *
 * It owns [renderer]: [open] is honoured once (the wasm renderer rejects a second, concurrent open) and
 * [dispose] closes it. Rendering is demand-driven — a page asks with [requestRender] while it is composed and
 * gives up with [releasePage] when it scrolls away, which cancels its in-flight render. A page keeps showing its
 * last bitmap (drawn scaled) until a render at the new width lands, so zoom never blanks a page; while one is
 * already shown, a new width waits [ZOOM_SETTLE_MS] first, so a pinch renders once when it settles rather than
 * once per frame. Single-threaded: call from the UI thread, with [scope] on the UI dispatcher.
 */
class PdfViewerState(
    private val renderer: PdfPageRenderer,
    private val scope: CoroutineScope,
    /** Called once per failed download; the host routes it to the global notification queue. */
    private val onDownloadFailed: () -> Unit = {},
) {
    private class PendingRender(
        val widthPx: Int,
        val job: Job,
    )

    private val _loadState = MutableStateFlow<PdfLoadState>(PdfLoadState.Loading)
    val loadState: StateFlow<PdfLoadState> = _loadState.asStateFlow()

    private val _zoom = MutableStateFlow(PdfZoom.FIT_WIDTH)
    val zoom: StateFlow<Float> = _zoom.asStateFlow()

    /** 1-based page shown at the top of the viewport. */
    private val _currentPage = MutableStateFlow(1)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    /** 0-based page index the list should scroll to, until the UI reports it with [onJumpConsumed]. */
    private val _pendingJump = MutableStateFlow<Int?>(null)
    val pendingJump: StateFlow<Int?> = _pendingJump.asStateFlow()

    /** Latest bitmap of each composed page, possibly at a previous zoom's width. */
    private val _pageBitmaps = MutableStateFlow<Map<Int, ImageBitmap>>(emptyMap())
    val pageBitmaps: StateFlow<Map<Int, ImageBitmap>> = _pageBitmaps.asStateFlow()

    private val _failedPages = MutableStateFlow<Set<Int>>(emptySet())
    val failedPages: StateFlow<Set<Int>> = _failedPages.asStateFlow()

    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    private val log = Logger.withTag("PdfViewer")
    private val cache = PageBitmapCache(BITMAP_CACHE_BYTES)
    private val pending = mutableMapOf<Int, PendingRender>()
    private var opened = false
    private var openJob: Job? = null
    private var disposed = false

    val pageCount: Int
        get() = (_loadState.value as? PdfLoadState.Ready)?.info?.pages?.size ?: 0

    /** Opens [source] once; later calls are ignored, since a renderer instance serves exactly one document. */
    fun open(source: PdfSource) {
        if (opened || disposed) return
        opened = true
        openJob = scope.launch { _loadState.value = openOutcome(source) }
    }

    fun zoomIn() = setZoom(PdfZoom.zoomIn(_zoom.value))

    fun zoomOut() = setZoom(PdfZoom.zoomOut(_zoom.value))

    fun fitWidth() = setZoom(PdfZoom.FIT_WIDTH)

    fun setZoom(scale: Float) {
        _zoom.value = PdfZoom.clamp(scale)
    }

    /** Called by the list as it scrolls; [index] is the 0-based first visible page. */
    fun onFirstVisiblePage(index: Int) {
        _currentPage.value = clampPage(index + 1)
    }

    /** Scrolls to 1-based [page], clamped to `1..pageCount`. */
    fun requestJump(page: Int) {
        val target = clampPage(page)
        _currentPage.value = target
        _pendingJump.value = target - 1
    }

    fun onJumpConsumed() {
        _pendingJump.value = null
    }

    /** Shows page [pageIndex] at [widthPx]: from the cache, or rendered, replacing any render at another width. */
    fun requestRender(
        pageIndex: Int,
        widthPx: Int,
    ) {
        if (disposed || pending[pageIndex]?.widthPx == widthPx) return
        val cached = cache.get(pageIndex, widthPx)
        if (cached != null) {
            cancelPending(pageIndex)
            show(pageIndex, cached)
            return
        }
        cancelPending(pageIndex)
        val settle = pageIndex in _pageBitmaps.value
        pending[pageIndex] = PendingRender(widthPx, scope.launch { render(pageIndex, widthPx, settle) })
    }

    /** The page scrolled out of composition: stop its render and drop its bitmap (the cache may keep a copy). */
    fun releasePage(pageIndex: Int) {
        cancelPending(pageIndex)
        _pageBitmaps.update { it - pageIndex }
    }

    /**
     * Reads the document bytes and hands them to [save], together with a `onFailed` to call if saving fails.
     * A read failure, or `onFailed`, reports through [onDownloadFailed] once; a saver that was merely cancelled
     * by the user calls nothing, so a cancel is never shown as a failure. No failure state is kept.
     */
    fun download(save: (bytes: ByteArray, onFailed: () -> Unit) -> Unit) {
        if (_downloading.value || disposed) return
        _downloading.value = true
        scope.launch {
            try {
                save(renderer.readBytes(), onceOnly { reportDownloadFailed("the file could not be saved") })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.e(e) { "download: reading the document bytes failed" }
                onDownloadFailed()
            } finally {
                _downloading.value = false
            }
        }
    }

    /** Cancels the open and every render, frees the bitmaps and closes the renderer; idempotent. */
    fun dispose() {
        if (disposed) return
        disposed = true
        openJob?.cancel()
        pending.values.forEach { it.job.cancel() }
        pending.clear()
        cache.clear()
        _pageBitmaps.value = emptyMap()
        renderer.close()
    }

    private suspend fun openOutcome(source: PdfSource): PdfLoadState =
        try {
            PdfLoadState.Ready(renderer.open(source))
        } catch (e: CancellationException) {
            throw e
        } catch (e: PdfLoadException) {
            // An expected outcome (a corrupt or protected file, no network) that the viewer shows to the user:
            // one line, no stack trace. The stack is kept for the unexpected failures below.
            log.i { "open failed: ${e.error}" + (e.cause?.message?.let { " ($it)" } ?: "") }
            PdfLoadState.Failed(e.error)
        } catch (e: Throwable) {
            log.e(e) { "open failed unexpectedly; shown as Corrupt" }
            // Renderers classify every failure they understand; anything else means the file could not be read.
            PdfLoadState.Failed(PdfLoadError.Corrupt)
        }

    private suspend fun render(
        pageIndex: Int,
        widthPx: Int,
        settle: Boolean,
    ) {
        if (settle) delay(ZOOM_SETTLE_MS)
        try {
            val bitmap = renderer.render(pageIndex, widthPx)
            cache.put(pageIndex, widthPx, bitmap)
            show(pageIndex, bitmap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.e(e) { "render of page $pageIndex at ${widthPx}px failed" }
            _failedPages.update { it + pageIndex }
        } finally {
            val self = currentCoroutineContext()[Job]
            if (pending[pageIndex]?.job === self) pending.remove(pageIndex)
        }
    }

    private fun show(
        pageIndex: Int,
        bitmap: ImageBitmap,
    ) {
        _pageBitmaps.update { it + (pageIndex to bitmap) }
        _failedPages.update { it - pageIndex }
    }

    private fun cancelPending(pageIndex: Int) {
        pending.remove(pageIndex)?.job?.cancel()
    }

    private fun reportDownloadFailed(reason: String) {
        log.w { "download: $reason" }
        onDownloadFailed()
    }

    private fun onceOnly(action: () -> Unit): () -> Unit {
        var done = false
        return {
            if (!done) {
                done = true
                action()
            }
        }
    }

    private fun clampPage(page: Int): Int = page.coerceIn(1, pageCount.coerceAtLeast(1))

    companion object {
        /** How long a zoomed page waits for the zoom to stop changing before it re-renders. */
        const val ZOOM_SETTLE_MS = 150L

        /** Rendered-page cache: about eight A4 pages at 1600 px wide. */
        const val BITMAP_CACHE_BYTES = 128L * 1024 * 1024
    }
}
