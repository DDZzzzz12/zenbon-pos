package org.example.project.core.presentation.components.docUtil

import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentMimeTypeTest {
    private val pdfBytes = "%PDF-1.7\n%âãÏÓ\n1 0 obj".encodeToByteArray()
    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)
    private val xmlBytes = "<?xml version=\"1.0\"?><Invoice/>".encodeToByteArray()
    private val unknownBytes = "just some text".encodeToByteArray()

    @Test
    fun pdfContentIsAPdfWhateverTheName() {
        assertEquals(DocumentMimeType.PDF, DocumentMimeType.resolve("Z-Bon.pdf", pdfBytes))
        assertEquals(DocumentMimeType.PDF, DocumentMimeType.resolve("PDF Preview", pdfBytes))
        assertEquals(DocumentMimeType.PDF, DocumentMimeType.resolve("scan.jpg", pdfBytes))
    }

    @Test
    fun pdfHeaderAfterLeadingJunkIsStillAPdf() {
        val prefixed = ByteArray(200) { 0x20 } + pdfBytes
        assertEquals(DocumentMimeType.PDF, DocumentMimeType.resolve("beleg", prefixed))
    }

    @Test
    fun imageAndXmlContentIsRecognizedByItsMagicBytes() {
        assertEquals("image/jpeg", DocumentMimeType.resolve("beleg", jpegBytes))
        assertEquals("image/png", DocumentMimeType.resolve("beleg.pdf", pngBytes))
        assertEquals("application/xml", DocumentMimeType.resolve("PDF Preview", xmlBytes))
    }

    @Test
    fun xmlWithByteOrderMarkIsXml() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals("application/xml", DocumentMimeType.resolve("x", bom + xmlBytes))
    }

    @Test
    fun unrecognizedContentFallsBackToTheFileExtension() {
        assertEquals("image/jpeg", DocumentMimeType.resolve("Kassenbon.JPEG", unknownBytes))
        assertEquals("image/jpeg", DocumentMimeType.resolve("bon.jpg", unknownBytes))
        assertEquals("image/png", DocumentMimeType.resolve("bon.png", unknownBytes))
        assertEquals("application/xml", DocumentMimeType.resolve("xrechnung.xml", unknownBytes))
    }

    @Test
    fun anythingElseStaysAPdfAsBefore() {
        assertEquals(DocumentMimeType.PDF, DocumentMimeType.resolve("rechnung.pdf", unknownBytes))
        assertEquals(DocumentMimeType.PDF, DocumentMimeType.resolve("PDF Preview", unknownBytes))
        assertEquals(DocumentMimeType.PDF, DocumentMimeType.resolve("archive.tar.gz", unknownBytes))
        assertEquals(DocumentMimeType.PDF, DocumentMimeType.resolve("", ByteArray(0)))
    }
}
