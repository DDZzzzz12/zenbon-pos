package org.example.project.ui.components.pdf

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Conformance cases every [PdfPageRenderer] actual must satisfy; the wasm and Android actuals reuse them. */
class PdfPageRendererConformanceTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream("pdf/$name")) { "missing fixture $name" }
            .use { it.readBytes() }

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
                val info = renderer.open(PdfSource.Bytes(fixture("two-page.pdf")))
                assertEquals(2, info.pages.size)
                assertTrue(info.pages[0].heightPt > info.pages[0].widthPt)
                assertTrue(info.pages[1].widthPt > info.pages[1].heightPt)
            } finally {
                renderer.close()
            }
        }

    @Test
    fun rendersPageAtRequestedWidthWithMatchingAspectRatio() =
        runTest {
            val renderer = createPdfPageRenderer()
            try {
                val page = renderer.open(PdfSource.Bytes(fixture("two-page.pdf"))).pages[0]
                val image = renderer.render(0, 600)
                val expectedHeight = (600 * page.heightPt / page.widthPt).roundToInt()
                assertEquals(600, image.width)
                assertTrue(abs(image.height - expectedHeight) <= 1, "height ${image.height} vs $expectedHeight")
            } finally {
                renderer.close()
            }
        }

    @Test
    fun readBytesReturnsTheOpenedBytes() =
        runTest {
            val bytes = fixture("two-page.pdf")
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
    fun corruptDocumentIsReportedCorrupt() = runTest { assertEquals(PdfLoadError.Corrupt, openError(fixture("corrupt.pdf"))) }

    @Test
    fun encryptedDocumentIsReportedPasswordProtected() = runTest { assertEquals(PdfLoadError.PasswordProtected, openError(fixture("encrypted.pdf"))) }

    @Test
    fun nonPdfBytesAreReportedNotPdf() = runTest { assertEquals(PdfLoadError.NotPdf, openError(fixture("not-a-pdf.xml"))) }

    @Test
    fun emptyBytesAreReportedEmpty() = runTest { assertEquals(PdfLoadError.Empty, openError(ByteArray(0))) }

    @Test
    fun urlSourceIsUnsupportedOnThisPlatform() =
        runTest {
            val renderer = createPdfPageRenderer()
            val error =
                try {
                    renderer.open(PdfSource.Url("http://localhost/x.pdf") { null })
                    null
                } catch (e: PdfLoadException) {
                    e.error
                } finally {
                    renderer.close()
                }
            assertEquals(PdfLoadError.Unsupported, error)
        }

    @Test
    fun closeTwiceDoesNotThrow() {
        val renderer = createPdfPageRenderer()
        renderer.close()
        renderer.close()
    }

    @Test
    fun zeroSizeCropBoxIsReportedCorrupt() = runTest { assertEquals(PdfLoadError.Corrupt, openError(fixture("zero-crop-box.pdf"))) }

    @Test
    fun readBytesAfterFailedOpenThrows() =
        runTest {
            val renderer = createPdfPageRenderer()
            try {
                assertFailsWith<PdfLoadException> { renderer.open(PdfSource.Bytes(fixture("zero-crop-box.pdf"))) }
                assertFailsWith<IllegalStateException> { renderer.readBytes() }
            } finally {
                renderer.close()
            }
        }
}
