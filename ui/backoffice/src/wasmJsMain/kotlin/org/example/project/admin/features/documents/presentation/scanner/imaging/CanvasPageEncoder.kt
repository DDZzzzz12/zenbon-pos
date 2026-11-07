@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.admin.features.documents.presentation.scanner.imaging

import kotlinx.coroutines.await
import org.example.project.admin.core.util.camera.CanvasImage
import org.example.project.admin.core.util.camera.ScanImage
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toByteArray
import org.w3c.dom.HTMLCanvasElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

private fun jpegBufferJs(
    canvas: HTMLCanvasElement,
    quality: Double,
): Promise<JsAny?> =
    js(
        "new Promise(function (resolve, reject) { canvas.toBlob(function (b) { if (!b) { reject(new Error('toBlob failed')); return; } b.arrayBuffer().then(resolve, reject); }, 'image/jpeg', quality); })",
    )

class CanvasPageEncoder : PageEncoder {
    override suspend fun toJpeg(
        image: ScanImage,
        quality: Float,
    ): ByteArray {
        val buffer = jpegBufferJs((image as CanvasImage).canvas, quality.toDouble()).await<JsAny?>() as ArrayBuffer
        return Int8Array(buffer).toByteArray()
    }
}
