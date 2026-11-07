@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.ui.components.pdf

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toByteArray
import org.khronos.webgl.toInt8Array
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.math.roundToInt

// --- PDF.js glue. Every promise below resolves (never rejects) to a plain result object, so error
// classification happens on PDF.js's own exception names rather than on Kotlin's wrapping of a JS throw.

private fun byteLengthJs(buffer: ArrayBuffer): Int = js("buffer.byteLength")

/** `%PDF-` within the first [window] bytes (ISO 32000 tolerates leading junk; the desktop actual does the same). */
private fun hasPdfMagicJs(
    buffer: ArrayBuffer,
    window: Int,
): Boolean =
    js(
        """(function () {
            var b = new Uint8Array(buffer, 0, Math.min(buffer.byteLength, window));
            for (var i = 0; i + 5 <= b.length; i++) {
                if (b[i] === 0x25 && b[i + 1] === 0x50 && b[i + 2] === 0x44 && b[i + 3] === 0x46 && b[i + 4] === 0x2D) return true;
            }
            return false;
        })()""",
    )

private fun int8ArrayBufferJs(bytes: Int8Array): ArrayBuffer = js("bytes.buffer")

// Opens [buffer] (transferred to the worker, so the caller must not reuse it) and reads every page's displayed
// size: getViewport({ scale: 1 }) is the crop box with /Rotate applied, in points. PDF.js stays in its default
// lenient mode (recoverable errors are skipped, like PDFBox), and falls back to the MediaBox when the CropBox
// is degenerate — so unlike the desktop actual, a zero-size CropBox opens instead of failing Corrupt.
// -> { task, doc, sizes: [w0, h0, w1, h1, ...] } or { error: <PDF.js exception name> }
private fun openDocumentJs(
    pdfjs: JsAny,
    buffer: ArrayBuffer,
): Promise<JsAny?> =
    js(
        """(function () {
            var task = pdfjs.lib.getDocument({
                data: new Uint8Array(buffer),
                worker: pdfjs.worker,
                BinaryDataFactory: pdfjs.BinaryDataFactory,
                isEvalSupported: false,
                enableXfa: false
            });
            return task.promise.then(function (doc) {
                var pages = [];
                for (var i = 1; i <= doc.numPages; i++) pages.push(doc.getPage(i));
                return Promise.all(pages).then(function (loaded) {
                    var sizes = [];
                    loaded.forEach(function (page) {
                        var vp = page.getViewport({ scale: 1 });
                        sizes.push(vp.width, vp.height);
                    });
                    return { task: task, doc: doc, sizes: sizes };
                });
            }).catch(function (e) {
                task.destroy().catch(function () {});
                return { error: (e && e.name) || String(e) };
            });
        })()""",
    )

private fun openErrorJs(result: JsAny): String? = js("result.error || null")

private fun openTaskJs(result: JsAny): JsAny = js("result.task")

private fun openDocJs(result: JsAny): JsAny = js("result.doc")

private fun sizeCountJs(result: JsAny): Int = js("result.sizes.length")

private fun sizeAtJs(
    result: JsAny,
    index: Int,
): Double = js("result.sizes[index]")

private fun destroyTaskJs(task: JsAny): Unit = js("{ task.destroy().catch(function () {}); }")

// Hands the loading task of an open whose caller went away to [onTask] once it settles (a failed open has
// already destroyed its own task in openDocumentJs and carries none).
private fun whenOpenSettlesJs(
    opened: Promise<JsAny?>,
    onTask: (JsAny) -> Unit,
): Unit = js("{ opened.then(function (result) { if (result && result.task) onTask(result.task); }); }")

// Starts rendering page [pageNumber] (1-based) onto a white widthPx x heightPx canvas, scaled independently
// on each axis so the bitmap is exactly the size the contract promises (the desktop actual does the same).
// -> { promise, cancel } where promise resolves to { pixels: the canvas's RGBA Uint8ClampedArray } or { error }.
private fun startRenderJs(
    doc: JsAny,
    pageNumber: Int,
    widthPx: Int,
    heightPx: Int,
): JsAny =
    js(
        """(function () {
            var renderTask = null;
            var cancelled = false;
            var promise = doc.getPage(pageNumber).then(function (page) {
                if (cancelled) throw { name: 'RenderingCancelledException' };
                var base = page.getViewport({ scale: 1 });
                var viewport = page.getViewport({ scale: widthPx / base.width });
                var canvas = document.createElement('canvas');
                canvas.width = widthPx;
                canvas.height = heightPx;
                var ctx = canvas.getContext('2d', { willReadFrequently: true });
                ctx.fillStyle = '#ffffff';
                ctx.fillRect(0, 0, widthPx, heightPx);
                renderTask = page.render({
                    canvas: canvas,
                    canvasContext: ctx,
                    viewport: viewport,
                    transform: [1, 0, 0, heightPx / viewport.height, 0, 0],
                    background: '#ffffff'
                });
                return renderTask.promise.then(function () {
                    var data = ctx.getImageData(0, 0, widthPx, heightPx).data;
                    canvas.width = 0;
                    canvas.height = 0;
                    return { pixels: data };
                });
            }).catch(function (e) {
                return { error: (e && e.name) || String(e) };
            });
            return {
                promise: promise,
                cancel: function () { cancelled = true; if (renderTask) renderTask.cancel(); }
            };
        })()""",
    )

private fun renderPromiseJs(handle: JsAny): Promise<JsAny?> = js("handle.promise")

private fun cancelRenderJs(handle: JsAny): Unit = js("{ handle.cancel(); }")

private fun renderErrorJs(result: JsAny): String? = js("result.error || null")

private fun renderPixelsJs(result: JsAny): JsAny = js("result.pixels")

/**
 * wasm [PdfPageRenderer] backed by PDF.js (see [PdfJsLoader]). Pages are rasterised onto a canvas and copied
 * into a Skia bitmap ([rgbaPixelsToImageBitmap]). JS is single-threaded, so no lock is needed; a [generation]
 * counter makes an open or render that was overtaken by [close] or a newer [open] fail instead of touching a
 * destroyed document. A PDF.js chunk that fails to download is reported as [PdfLoadError.Network].
 */
internal class PdfJsPageRenderer(
    private val fetch: PdfJsFetch = PdfJsFetch(),
    /** Test seam: observes every loading task this renderer destroys, after its destroy() was started. */
    private val onTaskDisposed: (JsAny) -> Unit = {},
) : PdfPageRenderer {
    private var task: JsAny? = null
    private var doc: JsAny? = null
    private var pages: List<PdfPageSize> = emptyList()
    private var source: PdfSource? = null
    private var generation = 0

    override suspend fun open(source: PdfSource): PdfDocumentInfo {
        close()
        val opening = generation
        val buffer = loadBuffer(source)
        if (byteLengthJs(buffer) == 0) throw PdfLoadException(PdfLoadError.Empty)
        if (!hasPdfMagicJs(buffer, MAGIC_WINDOW)) throw PdfLoadException(PdfLoadError.NotPdf)
        val opened = openDocumentJs(loadPdfJs(), buffer)
        val result =
            try {
                opened.await<JsAny?>()!!
            } catch (e: CancellationException) {
                // The promise still settles later with a live document in the shared worker: destroy it then.
                whenOpenSettlesJs(opened) { orphan -> dispose(orphan) }
                throw e
            }
        openErrorJs(result)?.let { throw PdfLoadException(errorFor(it)) }
        if (opening != generation) {
            dispose(openTaskJs(result))
            throw IllegalStateException("renderer was closed or reopened while opening")
        }
        val sizes = pageSizesOrDestroy(result)
        this.task = openTaskJs(result)
        this.doc = openDocJs(result)
        this.pages = sizes
        this.source = source
        return PdfDocumentInfo(sizes)
    }

    override suspend fun render(
        pageIndex: Int,
        widthPx: Int,
    ): ImageBitmap {
        require(widthPx > 0) { "widthPx must be positive: $widthPx" }
        val document = checkNotNull(doc) { "render() called without an open document" }
        val size = pages.getOrNull(pageIndex) ?: throw IndexOutOfBoundsException("page $pageIndex of ${pages.size}")
        val heightPx = (widthPx * size.heightPt / size.widthPt).roundToInt().coerceAtLeast(1)
        currentCoroutineContext().ensureActive()
        val rendering = generation
        val handle = startRenderJs(document, pageIndex + 1, widthPx, heightPx)
        val result =
            try {
                renderPromiseJs(handle).await<JsAny?>()!!
            } catch (e: CancellationException) {
                cancelRenderJs(handle)
                throw e
            }
        currentCoroutineContext().ensureActive()
        check(rendering == generation) { "renderer was closed or reopened while rendering" }
        renderErrorJs(result)?.let { error("PDF.js could not render page $pageIndex: $it") }
        return rgbaPixelsToImageBitmap(renderPixelsJs(result), widthPx, heightPx)
    }

    override suspend fun readBytes(): ByteArray =
        when (val opened = checkNotNull(source) { "readBytes() called before a successful open()" }) {
            is PdfSource.Bytes -> opened.bytes
            is PdfSource.Url -> Int8Array(fetch.fetch(opened)).toByteArray()
        }

    override fun close() {
        generation++
        task?.let { dispose(it) }
        task = null
        doc = null
        pages = emptyList()
        source = null
    }

    /** A [PdfSource.Url] is fetched straight into an ArrayBuffer; [PdfSource.Bytes] pays the one per-byte copy. */
    private suspend fun loadBuffer(source: PdfSource): ArrayBuffer =
        when (source) {
            is PdfSource.Bytes -> int8ArrayBufferJs(source.bytes.toInt8Array())
            is PdfSource.Url -> fetch.fetch(source)
        }

    private suspend fun loadPdfJs(): JsAny =
        try {
            PdfJsLoader.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw PdfLoadException(PdfLoadError.Network, e)
        }

    private fun pageSizesOrDestroy(result: JsAny): List<PdfPageSize> {
        val sizes =
            (0 until sizeCountJs(result) step 2).map { i ->
                PdfPageSize(sizeAtJs(result, i).toFloat(), sizeAtJs(result, i + 1).toFloat())
            }
        val error =
            when {
                sizes.isEmpty() -> PdfLoadError.Empty
                sizes.any { !it.isRenderable() } -> PdfLoadError.Corrupt
                else -> null
            }
        if (error != null) {
            dispose(openTaskJs(result))
            throw PdfLoadException(error)
        }
        return sizes
    }

    private fun dispose(loadingTask: JsAny) {
        destroyTaskJs(loadingTask)
        onTaskDisposed(loadingTask)
    }

    private fun PdfPageSize.isRenderable(): Boolean = widthPt.isFinite() && heightPt.isFinite() && widthPt > 0f && heightPt > 0f

    private fun errorFor(name: String): PdfLoadError =
        when (name) {
            "PasswordException" -> PdfLoadError.PasswordProtected
            // InvalidPDFException, FormatError, UnknownErrorException and anything else PDF.js cannot parse.
            else -> PdfLoadError.Corrupt
        }

    private companion object {
        const val MAGIC_WINDOW = 1024
    }
}
