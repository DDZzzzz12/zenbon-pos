package org.example.project.admin.features.documents.presentation.scanner.imaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PdfWriterTest {
    private fun page(label: String) = PdfPage(jpeg = "JPEG-$label".encodeToByteArray(), widthPx = 1500, heightPx = 2100)

    private fun pdfText(pages: List<PdfPage>): String {
        val result = PdfWriter.write(pages)
        assertIs<PdfWriteResult.Success>(result)
        return result.bytes.decodeToString()
    }

    @Test
    fun startsWithHeaderAndEndsWithEof() {
        val text = pdfText(listOf(page("a")))
        assertTrue(text.startsWith("%PDF-1.4\n"))
        assertTrue(text.endsWith("%%EOF\n"))
    }

    @Test
    fun pageCountMatches() {
        val text = pdfText(listOf(page("a"), page("b"), page("c")))
        assertEquals(3, Regex("/Type /Page /Parent").findAll(text).count())
        assertTrue(text.contains("/Count 3"))
    }

    @Test
    fun mediaBoxIsPixelsAt150Dpi() {
        // 1500 px / 150 dpi = 10 in = 720 pt; 2100 px = 14 in = 1008 pt.
        assertTrue(pdfText(listOf(page("a"))).contains("/MediaBox [0 0 720.0 1008.0]"))
    }

    @Test
    fun embedsTheJpegBytesUnmodified() {
        val text = pdfText(listOf(page("first"), page("second")))
        assertTrue(text.contains("JPEG-first"))
        assertTrue(text.contains("JPEG-second"))
        assertTrue(text.contains("/Filter /DCTDecode"))
    }

    @Test
    fun everyPageUsesDeviceRgb() {
        val text = pdfText(listOf(page("a"), page("b"), page("c")))
        assertEquals(3, Regex("/ColorSpace /DeviceRGB").findAll(text).count())
        assertTrue(!text.contains("/DeviceGray"))
    }

    @Test
    fun xrefOffsetsPointAtTheirObjects() {
        val text = pdfText(listOf(page("a"), page("b")))
        val startxref = Regex("startxref\n(\\d+)\n").find(text)!!.groupValues[1].toInt()
        assertTrue(text.substring(startxref).startsWith("xref\n"))
        val entries = Regex("(\\d{10}) 00000 n \n").findAll(text.substring(startxref)).map { it.groupValues[1].toInt() }.toList()
        assertEquals(2 + 3 * 2, entries.size, "catalog + page tree + 3 objects per page")
        entries.forEachIndexed { i, offset ->
            assertTrue(text.substring(offset).startsWith("${i + 1} 0 obj\n"), "object ${i + 1} at $offset")
        }
    }

    @Test
    fun streamLengthsMatchTheirData() {
        val text = pdfText(listOf(page("abc")))
        // The image object declares /Length 8 ("JPEG-abc").
        assertTrue(text.contains("/Length 8 >>\nstream\nJPEG-abc\nendstream"))
    }

    @Test
    fun tooLargeIsReportedWithTheSize() {
        val big = PdfPage(ByteArray(2_000), 100, 100)
        val result = PdfWriter.write(listOf(big), maxBytes = 1_000)
        assertIs<PdfWriteResult.TooLarge>(result)
        assertTrue(result.sizeBytes > 1_000)
    }

    @Test
    fun noPagesIsAProgrammerError() {
        var thrown = false
        try {
            PdfWriter.write(emptyList())
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
