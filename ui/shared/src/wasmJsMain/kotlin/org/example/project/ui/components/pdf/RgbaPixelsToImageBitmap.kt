@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.ui.components.pdf

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

// Bulk-copies the RGBA bytes into Skia's heap at [address]: one memcpy instead of a JS crossing per byte.
// False when this Skiko build does not expose its memory or the range does not fit inside it.
private fun copyIntoSkiaHeapJs(
    skiko: JsAny,
    address: Int,
    pixels: JsAny,
): Boolean =
    js(
        """(function () {
            var memory = skiko && skiko.wasmExports && skiko.wasmExports.memory;
            if (!memory || !(memory.buffer instanceof ArrayBuffer)) return false;
            if (address <= 0 || address + pixels.length > memory.buffer.byteLength) return false;
            new Uint8Array(memory.buffer, address, pixels.length).set(pixels);
            return true;
        })()""",
    )

private fun int32ViewJs(pixels: JsAny): JsAny = js("new Int32Array(pixels.buffer, pixels.byteOffset, pixels.byteLength >> 2)")

private fun pixelAtJs(
    pixels: JsAny,
    index: Int,
): Int = js("pixels[index]")

private suspend fun copyIntoSkiaHeap(
    address: Int,
    pixels: JsAny,
): Boolean = copyIntoSkiaHeapJs(awaitSkiko.await<JsAny>(), address, pixels)

/**
 * Wraps a canvas's RGBA bytes ([pixels], a `Uint8ClampedArray` of `widthPx * heightPx * 4`, unpremultiplied as
 * `getImageData` returns them) in a Compose [ImageBitmap].
 *
 * The fast path writes them straight into the new bitmap's storage in Skia's heap. [Bitmap.installPixels]
 * would instead cross into JS once per byte (Skiko's `skia_memSetByte`; measured ~1.3 s for a 2400 px page
 * against ~60 ms), so it is only the fallback. The fast path leans on Skiko internals (see [awaitSkiko]), so
 * ANY failure of it — a throw, a refusal, an unusable address, or a copy that Skia does not see — degrades
 * to the slow, correct path; it can cost time, never a wrong image.
 *
 * @param heapWriter test seam: writes [pixels] into Skia's heap at an address, false if it cannot.
 * @param onPathChosen test seam: told whether the fast path produced the bitmap.
 */
internal suspend fun rgbaPixelsToImageBitmap(
    pixels: JsAny,
    widthPx: Int,
    heightPx: Int,
    heapWriter: suspend (address: Int, pixels: JsAny) -> Boolean = ::copyIntoSkiaHeap,
    onPathChosen: (fast: Boolean) -> Unit = {},
): ImageBitmap {
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo(widthPx, heightPx, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
    val fast =
        try {
            fastCopy(bitmap, pixels, widthPx, heightPx, heapWriter)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
        }
    onPathChosen(fast)
    if (!fast) bitmap.installPixels(wordwiseCopy(pixels, widthPx * heightPx))
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}

/** True only if the pixels were written into [bitmap]'s storage AND Skia reads them back at both ends. */
private suspend fun fastCopy(
    bitmap: Bitmap,
    pixels: JsAny,
    widthPx: Int,
    heightPx: Int,
    heapWriter: suspend (Int, JsAny) -> Boolean,
): Boolean {
    val address = bitmap.peekPixels()?.let { pixmap -> pixmap.addr.also { pixmap.close() } } ?: return false
    // A NativePointer past 2 GiB would wrap negative; 0 is null.
    if (address <= 0 || bitmap.rowBytes != widthPx * 4) return false
    if (!heapWriter(address, pixels)) return false
    bitmap.notifyPixelsChanged()
    val words = int32ViewJs(pixels)
    val last = widthPx * heightPx - 1
    return skiaSees(bitmap.getColor(0, 0), pixelAtJs(words, 0)) &&
        skiaSees(bitmap.getColor(widthPx - 1, heightPx - 1), pixelAtJs(words, last))
}

/**
 * Whether Skia's ARGB [argb] is the little-endian RGBA canvas word [rgbaWord]. An opaque pixel is compared in
 * full; for a translucent one only alpha is, since Skia may round the colour of an unpremultiplied pixel.
 */
private fun skiaSees(
    argb: Int,
    rgbaWord: Int,
): Boolean {
    val alpha = rgbaWord ushr 24
    if (alpha != 0xFF) return argb ushr 24 == alpha
    val red = rgbaWord and 0xFF
    val green = (rgbaWord shr 8) and 0xFF
    val blue = (rgbaWord shr 16) and 0xFF
    return argb == (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}

/** Reads 32-bit words (a quarter of the crossings of a byte-wise read) and unpacks the little-endian RGBA. */
private fun wordwiseCopy(
    pixels: JsAny,
    count: Int,
): ByteArray {
    val words = int32ViewJs(pixels)
    val bytes = ByteArray(count * 4)
    for (i in 0 until count) {
        val word = pixelAtJs(words, i)
        val offset = i * 4
        bytes[offset] = word.toByte()
        bytes[offset + 1] = (word shr 8).toByte()
        bytes[offset + 2] = (word shr 16).toByte()
        bytes[offset + 3] = (word ushr 24).toByte()
    }
    return bytes
}
