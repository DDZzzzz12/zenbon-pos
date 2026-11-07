package org.example.project.ui.components.pdf

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Task 2's conformance cases (desktop `PdfPageRendererConformanceTest`) run against the PDF.js actual with
 * [PdfSource.Bytes]. The one desktop case that does not carry over is "Url is unsupported": wasm supports
 * it, and [PdfJsUrlSourceTest] covers it instead; and a zero-size CropBox opens rather than failing (see
 * [zeroSizeCropBoxFallsBackToTheMediaBox]). Fixtures come from [PdfFixtures], never resource files.
 */
class PdfPageRendererWasmTest {
    private suspend fun openError(bytes: ByteArray): PdfLoadError {
        val renderer = createPdfPageRenderer()
        try {
            renderer.open(PdfSource.Bytes(bytes))
        } catch (e: PdfLoadException) {
            return e.error
        } finally {
            renderer.close()
        }
        error("open() should have thrown PdfLoadException")
    }

    @Test
    fun opensTwoPageDocumentWithPageSizes() =
        runTest {
            val renderer = createPdfPageRenderer()
            try {
                val info = renderer.open(PdfSource.Bytes(PdfFixtures.twoPage))
                assertEquals(listOf(PdfPageSize(595f, 842f), PdfPageSize(842f, 595f)), info.pages)
            } finally {
                renderer.close()
            }
        }

    @Test
    fun rendersPageAtRequestedWidthWithMatchingAspectRatio() =
        runTest {
            val renderer = createPdfPageRenderer()
            try {
                val pages = renderer.open(PdfSource.Bytes(PdfFixtures.twoPage)).pages
                for ((index, page) in pages.withIndex()) {
                    val image = renderer.render(index, 600)
                    val expectedHeight = (600 * page.heightPt / page.widthPt).roundToInt()
                    assertEquals(600, image.width)
                    assertTrue(abs(image.height - expectedHeight) <= 1, "page $index height ${image.height} vs $expectedHeight")
                }
            } finally {
                renderer.close()
            }
        }

    @Test
    fun renderedPageCarriesInk() =
        runTest {
            val renderer = createPdfPageRenderer()
            try {
                renderer.open(PdfSource.Bytes(PdfFixtures.twoPage))
                val image = renderer.render(0, 300)
                val pixels = IntArray(image.width * image.height)
                image.readPixels(pixels)
                assertTrue(pixels.any { it != WHITE }, "page 1 rendered blank")
                assertEquals(WHITE, pixels[pixels.size - 1], "the background is filled white, not transparent")
            } finally {
                renderer.close()
            }
        }

    @Test
    fun readBytesReturnsTheOpenedBytes() =
        runTest {
            val bytes = PdfFixtures.twoPage
            val renderer = createPdfPageRenderer()
            try {
                renderer.open(PdfSource.Bytes(bytes))
                assertContentEquals(bytes, renderer.readBytes())
            } finally {
                renderer.close()
            }
        }

    @Test
    fun readBytesBeforeOpenThrows() =
        runTest {
            val renderer = createPdfPageRenderer()
            assertFailsWith<IllegalStateException> { renderer.readBytes() }
        }

    @Test
    fun renderBeforeOpenThrows() =
        runTest {
            val renderer = createPdfPageRenderer()
            assertFailsWith<IllegalStateException> { renderer.render(0, 100) }
        }

    @Test
    fun corruptDocumentIsReportedCorrupt() = runTest { assertEquals(PdfLoadError.Corrupt, openError(PdfFixtures.corrupt)) }

    @Test
    fun encryptedDocumentIsReportedPasswordProtected() = runTest { assertEquals(PdfLoadError.PasswordProtected, openError(PdfFixtures.encrypted)) }

    @Test
    fun nonPdfBytesAreReportedNotPdf() = runTest { assertEquals(PdfLoadError.NotPdf, openError(PdfFixtures.notAPdf)) }

    @Test
    fun emptyBytesAreReportedEmpty() = runTest { assertEquals(PdfLoadError.Empty, openError(ByteArray(0))) }

    /**
     * Deliberate divergence from the desktop actual (which reports Corrupt): PDF.js replaces a degenerate
     * CropBox with the MediaBox, so the page opens at the MediaBox size and is renderable. The contract's
     * guarantee — every reported page size is positive and finite — holds either way.
     */
    @Test
    fun zeroSizeCropBoxFallsBackToTheMediaBox() =
        runTest {
            val renderer = createPdfPageRenderer()
            try {
                assertEquals(listOf(PdfPageSize(595f, 842f)), renderer.open(PdfSource.Bytes(PdfFixtures.zeroCropBox)).pages)
                assertEquals(100, renderer.render(0, 100).width)
            } finally {
                renderer.close()
            }
        }

    @Test
    fun closeTwiceDoesNotThrow() {
        val renderer = createPdfPageRenderer()
        renderer.close()
        renderer.close()
    }

    @Test
    fun readBytesAfterFailedOpenThrows() =
        runTest {
            val renderer = createPdfPageRenderer()
            try {
                assertFailsWith<PdfLoadException> { renderer.open(PdfSource.Bytes(PdfFixtures.corrupt)) }
                assertFailsWith<IllegalStateException> { renderer.readBytes() }
            } finally {
                renderer.close()
            }
        }

    @Test
    fun renderAfterCloseThrows() =
        runTest {
            val renderer = createPdfPageRenderer()
            renderer.open(PdfSource.Bytes(PdfFixtures.twoPage))
            renderer.close()
            assertFailsWith<IllegalStateException> { renderer.render(0, 100) }
        }

    @Test
    fun reopeningReplacesTheDocument() =
        runTest {
            val renderer = createPdfPageRenderer()
            try {
                renderer.open(PdfSource.Bytes(PdfFixtures.twoPage))
                assertFailsWith<PdfLoadException> { renderer.open(PdfSource.Bytes(PdfFixtures.corrupt)) }
                assertFailsWith<IllegalStateException> { renderer.render(0, 100) }
                assertEquals(2, renderer.open(PdfSource.Bytes(PdfFixtures.twoPage)).pages.size)
            } finally {
                renderer.close()
            }
        }

    private companion object {
        const val WHITE = -1 // 0xFFFFFFFF, ARGB
    }
}
