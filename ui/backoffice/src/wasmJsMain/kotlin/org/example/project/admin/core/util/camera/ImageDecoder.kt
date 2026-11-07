@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.admin.core.util.camera

import kotlinx.browser.document
import kotlinx.coroutines.await
import org.khronos.webgl.toInt8Array
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.Promise

private fun decodeToCanvasJs(
    url: String,
    canvas: HTMLCanvasElement,
    maxLongEdge: Int,
): Promise<JsAny?> =
    js(
        "new Promise(function (resolve, reject) { var img = new Image(); img.onload = function () { var w = img.naturalWidth, h = img.naturalHeight; var m = Math.max(w, h); var k = m > maxLongEdge ? maxLongEdge / m : 1; canvas.width = Math.max(1, Math.round(w * k)); canvas.height = Math.max(1, Math.round(h * k)); canvas.getContext('2d', { willReadFrequently: true }).drawImage(img, 0, 0, canvas.width, canvas.height); resolve(null); }; img.onerror = function () { reject(new Error('decode failed')); }; img.src = url; })",
    )

/** A phone photo can be 12+ megapixels; the scanner never needs more than this on the long edge. */
private const val MAX_DECODE_LONG_EDGE = 3000

object ImageDecoder {
    /** Decodes JPEG/PNG [bytes] into a canvas, downscaled to at most 3000 px on the long edge. Throws if the browser cannot decode them. */
    suspend fun decode(
        bytes: ByteArray,
        mimeType: String,
    ): CanvasImage {
        val parts = JsArray<JsAny?>()
        parts[0] = bytes.toInt8Array()
        val url = URL.createObjectURL(Blob(parts, BlobPropertyBag(type = mimeType)))
        try {
            val canvas = document.createElement("canvas") as HTMLCanvasElement
            decodeToCanvasJs(url, canvas, MAX_DECODE_LONG_EDGE).await<JsAny?>()
            return CanvasImage(canvas)
        } finally {
            URL.revokeObjectURL(url)
        }
    }
}
