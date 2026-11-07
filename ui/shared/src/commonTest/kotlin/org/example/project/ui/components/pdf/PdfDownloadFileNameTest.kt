package org.example.project.ui.components.pdf

import kotlin.test.Test
import kotlin.test.assertEquals

class PdfDownloadFileNameTest {
    @Test
    fun aNameWithoutAnExtensionGetsDotPdf() {
        assertEquals("PDF-Vorschau.pdf", pdfDownloadFileName("PDF-Vorschau"))
    }

    @Test
    fun aNameEndingInPdfIsKeptAsIs() {
        assertEquals("Rechnung_2026-10-22_1c8e.pdf", pdfDownloadFileName("Rechnung_2026-10-22_1c8e.pdf"))
    }

    @Test
    fun theCheckIgnoresCase() {
        assertEquals("SCAN.PDF", pdfDownloadFileName("SCAN.PDF"))
        assertEquals("scan.Pdf", pdfDownloadFileName("scan.Pdf"))
    }

    @Test
    fun anotherExtensionIsNotReplacedButAppendedTo() {
        assertEquals("beleg.xml.pdf", pdfDownloadFileName("beleg.xml"))
    }

    @Test
    fun nothingElseIsSanitized() {
        assertEquals("Z-Bon 12/2026 ä.pdf", pdfDownloadFileName("Z-Bon 12/2026 ä"))
    }
}
