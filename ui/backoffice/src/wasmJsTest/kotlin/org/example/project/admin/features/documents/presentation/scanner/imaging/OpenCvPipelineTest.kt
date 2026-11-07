@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.admin.features.documents.presentation.scanner.imaging

import kotlinx.browser.document
import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.util.camera.CanvasImage
import org.w3c.dom.HTMLCanvasElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun drawSkewedPageJs(canvas: HTMLCanvasElement): Unit =
    js(
        """
(function () {
  canvas.width = 800; canvas.height = 600;
  var c = canvas.getContext('2d');
  c.fillStyle = '#202020'; c.fillRect(0, 0, 800, 600);
  c.fillStyle = '#f5f5f0';
  c.beginPath(); c.moveTo(150, 90); c.lineTo(650, 130); c.lineTo(620, 520); c.lineTo(110, 480); c.closePath(); c.fill();
})()
""",
    )

class OpenCvPipelineTest {
    // Known corners of the page drawn above, TL TR BR BL.
    private val expected = Quad(Pt(150f, 90f), Pt(650f, 130f), Pt(620f, 520f), Pt(110f, 480f))

    private fun page(): CanvasImage {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        drawSkewedPageJs(canvas)
        return CanvasImage(canvas)
    }

    @Test
    fun detectsTheSkewedPageWithinTolerance() =
        runTest {
            val detection = OpenCvDetector().detect(page())
            val distances = (0..3).map { QuadMath.distance(expected[it], detection.quad[it]) }
            println("OpenCvPipelineTest corner distances: $distances found=${detection.found}")
            assertTrue(detection.found, "a clear light page on a dark background must be found")
            for (i in 0..3) {
                assertTrue(distances[i] < 20f, "corner $i is ${distances[i]} px from the truth")
            }
        }

    @Test
    fun warpProducesTheExpectedAspectAndFilters() =
        runTest {
            val image = page()
            for (filter in ScanFilter.entries) {
                val out = OpenCvWarper().warp(image, expected, filter, maxLongEdge = 2000)
                val (w, h) = QuadMath.outputSize(expected, 2000)
                println("OpenCvPipelineTest warp $filter ${out.width}x${out.height}")
                assertEquals(w, out.width, "$filter width")
                assertEquals(h, out.height, "$filter height")
            }
        }

    @Test
    fun rotate90SwapsTheDimensions() =
        runTest {
            val out = OpenCvWarper().rotate90(page())
            println("OpenCvPipelineTest rotate90 ${out.width}x${out.height}")
            assertEquals(600, out.width)
            assertEquals(800, out.height)
        }
}
