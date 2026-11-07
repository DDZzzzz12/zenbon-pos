package org.example.project.pdf.generator

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The TSE block of the Kassenmeldung data sheet is read from whichever TSE is plugged in, and the
 * operator copies it into the ELSTER notification — so the sheet says where it came from.
 */
class GenerateKassenmeldungTest {
    private val base =
        ShopConfigurationDto.KassenMeldungResponseDto(
            betriebsstaetteName = "Test",
            betriebsstaetteStreet = "Weg 1",
            betriebsstaetteZip = "12345",
            betriebsstaetteCity = "Ort",
            betriebsstaetteCountry = "DE",
            kasseSeriennr = "K1",
            kasseBrand = "Zenbon",
            kasseModell = "Desktop/Server POS",
            kasseSwBrand = "Zenbon POS",
            kasseSwVersion = "1.1.0",
            artDesAufzeichnungssystems = "Computergestützte/PC-Kassensysteme",
            inbetriebnahme = "2026-05-01",
        )

    private fun text(data: ShopConfigurationDto.KassenMeldungResponseDto): String =
        Loader.loadPDF(generateKassenmeldungPdfBytes(data)).use { PDFTextStripper().getText(it) }.replace(Regex("\\s+"), " ")

    @Test
    fun `a connected TSE carries the source note`() {
        val text = text(base.copy(tseSerialNumberHex = "ABCDEF", tseBsiCertId = "0563-2023"))
        assertTrue("aktuell angeschlossenen TSE" in text, text)
        assertTrue("BSI-K-TR-0563-2023" in text, text)
    }

    @Test
    fun `no TSE connected means no source note`() {
        assertFalse("aktuell angeschlossenen TSE" in text(base))
    }
}
