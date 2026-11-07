@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.ui.components.pdf

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.khronos.webgl.toInt8Array
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

private fun pageCountJs(
    pdfjs: JsAny,
    data: JsAny,
): Promise<JsAny?> =
    js(
        """(function () {
            var task = pdfjs.lib.getDocument({ data: new Uint8Array(data.buffer.slice(0)), worker: pdfjs.worker });
            return task.promise.then(function (doc) { var n = doc.numPages; return task.destroy().then(function () { return n; }); });
        })()""",
    )

/**
 * Standing guard (model: adminApp's MoneyShimPropagationTest): PDF.js and its worker must resolve from the
 * npm package alone. A wasm library cannot rely on its own resource file — a dependency's
 * `wasmJsMain/resources` are not copied into a consumer's webpack directory — so if the loader or the
 * worker ever start needing one, these fail with a module-resolution or worker error, not a wrong value.
 */
class PdfJsBundlingGuardTest {
    @Test
    fun loaderResolvesPdfJsByBareSpecifier() =
        runTest {
            val first = PdfJsLoader.load()
            assertSame(first, PdfJsLoader.load(), "a successful load is cached")
        }

    @Test
    fun workerAnswersSoADocumentOpens() =
        runTest {
            // getDocument() is parsed by the worker, and a port-based PDFWorker never falls back to a main-thread
            // "fake" worker — so a worker chunk webpack failed to emit makes this hang or reject.
            val pages = pageCountJs(PdfJsLoader.load(), PdfFixtures.twoPage.toInt8Array()).await<JsAny?>()
            assertEquals(2, (pages as kotlin.js.JsNumber).toInt())
        }
}
