@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.ui.components.pdf

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

// A dynamic import() keeps PDF.js out of the consumer's main bundle: webpack splits it into its own chunk,
// fetched only when the first document is opened. The *legacy* build is used because the modern one calls
// built-ins (Map.prototype.getOrInsertComputed, Uint8Array.fromBase64) newer than the browsers Kotlin/Wasm
// itself supports; the legacy build carries polyfills for them.
//
// The worker is a real module worker created from the npm package with webpack's
// `new Worker(new URL(<bare specifier>, import.meta.url))` pattern, so webpack emits it into the CONSUMER's
// own output — no resource file has to propagate from shared-ui (dependency resources never do on wasm).
// One PDFWorker is shared by every document and never destroyed: passing it as `worker` to getDocument()
// keeps a loading task's destroy() from tearing down a worker another open document still uses.
//
// Image decoders: PDF.js decodes CCITT G4 and JBIG2 (jbig2.wasm) and JPEG 2000 (openjpeg.wasm) — the usual
// compressions of a scanned Beleg — with wasm modules it fetches at runtime from a `wasmUrl` directory. Without
// them it does not fail, it skips the image and the scan opens as a blank page. A directory of fixed file names
// cannot be produced from a library without a resource file or consumer webpack config, so instead each file
// is referenced as `new URL(<bare specifier>, import.meta.url)`: webpack emits it as an asset into the
// consumer's output (hashed name, under its publicPath) and we hand PDF.js a BinaryDataFactory — its hook for
// supplying cMap / font / wasm bytes — that maps the requested file name to that URL. The bytes are fetched
// same-origin only when an image needs them (no blob:, no eval). A custom factory turns PDF.js's in-worker
// fetching off, which also disables its ICC (qcms) module: ICC-tagged images fall back to their alternate
// colour space. cMaps and standard-font data are not bundled (dozens of files); non-embedded fonts use
// system-font substitution.
private fun importPdfJs(): Promise<JsAny?> =
    js(
        """import('pdfjs-dist/legacy/build/pdf.min.mjs').then(function (m) {
            var lib = m.getDocument ? m : m.default;
            var port = new Worker(
                new URL('pdfjs-dist/legacy/build/pdf.worker.min.mjs', import.meta.url),
                { type: 'module' }
            );
            var decoders = {
                'jbig2.wasm': new URL('pdfjs-dist/wasm/jbig2.wasm', import.meta.url),
                'openjpeg.wasm': new URL('pdfjs-dist/wasm/openjpeg.wasm', import.meta.url)
            };
            var BundledBinaryDataFactory = function () {};
            BundledBinaryDataFactory.prototype.fetch = function (request) {
                var url = request && request.kind === 'wasmUrl' ? decoders[request.filename] : undefined;
                if (!url) {
                    return Promise.reject(new Error('not bundled: ' + (request && request.kind) + ' ' + (request && request.filename)));
                }
                return fetch(url).then(function (response) {
                    if (!response.ok) throw new Error('HTTP ' + response.status + ' for ' + url);
                    return response.arrayBuffer();
                }).then(function (buffer) { return new Uint8Array(buffer); });
            };
            return { lib: lib, worker: new lib.PDFWorker({ port: port }), BinaryDataFactory: BundledBinaryDataFactory };
        })""",
    )

private fun looksReadyJs(pdfjs: JsAny?): Boolean = js("!!(pdfjs && pdfjs.lib && pdfjs.lib.getDocument && pdfjs.worker && pdfjs.BinaryDataFactory)")

/**
 * Loads PDF.js once and caches it. A failed load is not cached, so a retry can succeed.
 *
 * The handle is a JS object `{ lib, worker, BinaryDataFactory }`: `lib` is the PDF.js module, `worker` the shared
 * `PDFWorker`, `BinaryDataFactory` the class that serves the bundled image decoders (see above).
 */
internal object PdfJsLoader {
    private val loader = SharedPromiseLoader(start = ::importPdfJs, isReady = ::looksReadyJs)

    suspend fun load(): JsAny = loader.load()
}
