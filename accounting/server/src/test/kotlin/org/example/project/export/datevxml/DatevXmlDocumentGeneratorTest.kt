package org.example.project.export.datevxml

import org.example.project.core.domain.dto.datevxml.DatevXmlExportDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatevXmlDocumentGeneratorTest {
    private val config =
        DatevXmlExportDto.ExportConfigDto(
            kassenName = "Kasse",
            clientName = "Test Shop",
            generatingSystem = "POS-KMP",
            consultantNumber = "1234567",
            clientNumber = "12345",
        )

    @Test
    fun `generate with per-entry ledger files creates one document per entry`() {
        val ledgerFileNames =
            listOf(
                "Kasse_20230112_1.xml",
                "Kasse_20230112_2.xml",
                "Kasse_20230112_3.xml",
            )

        val pdfFileNamesByLedger =
            mapOf(
                "Kasse_20230112_2.xml" to "KA_03_Tankstelle.pdf",
            )

        val result =
            DatevXmlDocumentGenerator.generate(
                ledgerFileNames = ledgerFileNames,
                config = config,
                startDate = "2023-01-12",
                endDate = "2023-01-12",
                pdfFileNamesByLedger = pdfFileNamesByLedger,
            )

        val xml = String(result, Charsets.UTF_8)

        // Should have 3 <document> elements
        val docCount = "<document>".toRegex().findAll(xml).count()
        assertEquals(3, docCount, "should have 3 document elements")

        // Entry 2 should have File extension for its PDF
        assertTrue(xml.contains("datafile=\"Kasse_20230112_2.xml\""), "should reference ledger 2")
        assertTrue(xml.contains("name=\"KA_03_Tankstelle.pdf\""), "should link PDF to entry 2")
    }

    @Test
    fun `generate entry without PDF has no File extension`() {
        val ledgerFileNames = listOf("Kasse_20230112_1.xml")

        val result =
            DatevXmlDocumentGenerator.generate(
                ledgerFileNames = ledgerFileNames,
                config = config,
                startDate = "2023-01-12",
                endDate = "2023-01-12",
                pdfFileNamesByLedger = emptyMap(),
            )

        val xml = String(result, Charsets.UTF_8)
        assertTrue(xml.contains("datafile=\"Kasse_20230112_1.xml\""), "should reference ledger file")
        assertTrue(!xml.contains("type=\"File\""), "should have no File extension")
    }

    @Test
    fun `generate with legacy pdfFileNamesByDate still works`() {
        val ledgerFileNames = listOf("Kasse_20230112.xml")
        val pdfFileNamesByDate =
            mapOf(
                "2023-01-12" to listOf("receipt.pdf"),
            )

        val result =
            DatevXmlDocumentGenerator.generate(
                ledgerFileNames = ledgerFileNames,
                config = config,
                startDate = "2023-01-12",
                endDate = "2023-01-12",
                pdfFileNamesByDate = pdfFileNamesByDate,
            )

        val xml = String(result, Charsets.UTF_8)
        assertTrue(xml.contains("name=\"receipt.pdf\""), "legacy mode should still attach PDFs by date")
    }

    @Test
    fun `generate extracts period from sequenced filenames`() {
        val ledgerFileNames = listOf("Kasse_20230112_1.xml")

        val result =
            DatevXmlDocumentGenerator.generate(
                ledgerFileNames = ledgerFileNames,
                config = config,
                startDate = "2023-01-12",
                endDate = "2023-01-12",
            )

        val xml = String(result, Charsets.UTF_8)
        assertTrue(xml.contains("value=\"2023-01\""), "should extract period 2023-01 from sequenced filename")
    }
}
