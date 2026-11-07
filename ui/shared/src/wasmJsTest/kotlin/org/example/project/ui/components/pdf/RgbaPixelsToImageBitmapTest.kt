@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.ui.components.pdf

import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

// A 3x1 canvas row: opaque red, opaque green, opaque blue, as getImageData lays it out (R, G, B, A per pixel).
private fun redGreenBlueJs(): JsAny = js("new Uint8ClampedArray([255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255])")

/**
 * Both copy paths must keep the canvas's channel order (a swapped R/B would pass every black-and-white page),
 * and any failure of the fast path — a throw, a refusal, or a copy that silently wrote nothing — must degrade
 * to the slow path's correct bitmap rather than to a wrong or blank one.
 */
class RgbaPixelsToImageBitmapTest {
    private suspend fun render(heapWriter: (suspend (Int, JsAny) -> Boolean)? = null): Pair<IntArray, Boolean> {
        var fast: Boolean? = null
        val image =
            if (heapWriter == null) {
                rgbaPixelsToImageBitmap(redGreenBlueJs(), widthPx = 3, heightPx = 1, onPathChosen = { fast = it })
            } else {
                rgbaPixelsToImageBitmap(redGreenBlueJs(), 3, 1, heapWriter = heapWriter, onPathChosen = { fast = it })
            }
        assertEquals(3, image.width)
        assertEquals(1, image.height)
        return IntArray(3).also { image.readPixels(it) } to checkNotNull(fast) { "no path reported" }
    }

    @Test
    fun fastPathIsTakenAndKeepsChannelOrder() =
        runTest {
            val (argb, fast) = render()
            assertContentEquals(EXPECTED, argb)
            assertEquals(true, fast, "the real Skiko heap copy should be available in a Compose/Wasm bundle")
        }

    @Test
    fun refusedFastPathFallsBackToACorrectBitmap() =
        runTest {
            val (argb, fast) = render { _, _ -> false }
            assertContentEquals(EXPECTED, argb)
            assertEquals(false, fast)
        }

    @Test
    fun throwingFastPathFallsBackToACorrectBitmap() =
        runTest {
            val (argb, fast) = render { _, _ -> error("skiko module changed shape") }
            assertContentEquals(EXPECTED, argb)
            assertEquals(false, fast)
        }

    @Test
    fun fastCopyThatWroteNothingIsDetectedAndFallsBack() =
        runTest {
            // Claims success but never touched the heap: the post-copy pixel check must catch it.
            val (argb, fast) = render { _, _ -> true }
            assertContentEquals(EXPECTED, argb)
            assertEquals(false, fast)
        }

    private companion object {
        val EXPECTED = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
    }
}
