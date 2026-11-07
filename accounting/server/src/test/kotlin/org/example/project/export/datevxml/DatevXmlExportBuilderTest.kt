package org.example.project.export.datevxml

import org.example.project.core.domain.dto.datevxml.DatevXmlExportDto
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatevXmlExportBuilderTest {
    private val config =
        DatevXmlExportDto.ExportConfigDto(
            kassenName = "Kasse",
            clientName = "Test Shop",
        )

    @Test
    fun `buildExportPackage groups entries by headerId into one XML`() {
        // Z-Bon with 4 VAT lines → 1 XML with 4 cashLedger entries
        val entries =
            listOf(
                DatevXmlExportDto.CashLedgerEntryDto(
                    date = "2023-01-12",
                    amount = "3223.90",
                    tax = "7.00",
                    bookingText = "TE7%",
                    headerId = 100,
                ),
                DatevXmlExportDto.CashLedgerEntryDto(
                    date = "2023-01-12",
                    amount = "673.60",
                    tax = "19.00",
                    bookingText = "TE19%",
                    headerId = 100,
                ),
                DatevXmlExportDto.CashLedgerEntryDto(
                    date = "2023-01-12",
                    amount = "-2106.90",
                    bookingText = "EC-Karte",
                    headerId = 100,
                ),
                DatevXmlExportDto.CashLedgerEntryDto(
                    date = "2023-01-12",
                    amount = "-42.90",
                    bookingText = "Gutschein",
                    headerId = 100,
                ),
            )

        val zip =
            DatevXmlExportBuilder.buildExportPackage(
                entries = entries,
                config = config,
                startDate = "2023-01-12",
                endDate = "2023-01-12",
            )

        val fileNames = extractZipFileNames(zip)
        assertEquals(2, fileNames.size, "should have document.xml + 1 ledger XML")
        assertTrue(fileNames.contains("document.xml"))
        assertTrue(fileNames.contains("Kasse_20230112_1.xml"))

        // Verify all 4 entries are in one consolidate
        val contents = extractZipContents(zip)
        val xml = contents["Kasse_20230112_1.xml"]!!
        assertEquals(4, "<cashLedger>".toRegex().findAll(xml).count(), "should have 4 cashLedger entries")
        assertTrue(xml.contains("consolidatedAmount=\"1747.70\""), "consolidated should be sum: 3223.90+673.60-2106.90-42.90=1747.70")
    }

    @Test
    fun `buildExportPackage creates separate XMLs for different headers`() {
        val entries =
            listOf(
                DatevXmlExportDto.CashLedgerEntryDto(
                    date = "2023-01-12",
                    amount = "6352.00",
                    bookingText = "TE19%",
                    headerId = 1,
                ),
                DatevXmlExportDto.CashLedgerEntryDto(
                    date = "2023-01-12",
                    amount = "-89.50",
                    bookingText = "Tankrechnung",
                    headerId = 2,
                ),
                DatevXmlExportDto.CashLedgerEntryDto(
                    date = "2023-01-12",
                    amount = "-20.00",
                    bookingText = "Kino",
                    headerId = 3,
                ),
            )

        val zip =
            DatevXmlExportBuilder.buildExportPackage(
                entries = entries,
                config = config,
                startDate = "2023-01-12",
                endDate = "2023-01-12",
            )

        val fileNames = extractZipFileNames(zip)
        assertTrue(fileNames.contains("Kasse_20230112_1.xml"))
        assertTrue(fileNames.contains("Kasse_20230112_2.xml"))
        assertTrue(fileNames.contains("Kasse_20230112_3.xml"))
    }

    @Test
    fun `buildExportPackage mixed grouped and standalone entries`() {
        val entries =
            listOf(
                // Z-Bon: 2 VAT lines with shared PDF
                DatevXmlExportDto.CashLedgerEntryDto(
                    date = "2023-01-12",
                    amount = "3223.90",
                    tax = "7.00",
                    bookingText = "TE7%",
                    headerId = 100,
                    linkedPdfFileName = "ZBon_Nr1.pdf",
                ),
                DatevXmlExportDto.CashLedgerEntryDto(
                    date = "2023-01-12",
                    amount = "673.60",
                    tax = "19.00",
                    bookingText = "TE19%",
                    headerId = 100,
                    linkedPdfFileName = "ZBon_Nr1.pdf",
                ),
                // Standalone manual entry with own PDF
                DatevXmlExportDto.CashLedgerEntryDto(
                    date = "2023-01-12",
                    amount = "-89.50",
                    bookingText = "Tankrechnung",
                    headerId = 200,
                    linkedPdfFileName = "Tankrechnung.pdf",
                ),
            )

        val pdfAttachments =
            listOf(
                PdfAttachment(date = "2023-01-12", fileName = "ZBon_Nr1.pdf", data = "zbon".toByteArray()),
                PdfAttachment(date = "2023-01-12", fileName = "Tankrechnung.pdf", data = "tank".toByteArray()),
            )

        val zip =
            DatevXmlExportBuilder.buildExportPackage(
                entries = entries,
                config = config,
                startDate = "2023-01-12",
                endDate = "2023-01-12",
                pdfAttachments = pdfAttachments,
            )

        val fileNames = extractZipFileNames(zip)
        // 2 XMLs: one for Z-Bon (grouped), one for Tankrechnung (standalone)
        assertTrue(fileNames.contains("Kasse_20230112_1.xml"))
        assertTrue(fileNames.contains("Kasse_20230112_2.xml"))
        // 2 PDFs
        assertTrue(fileNames.contains("ZBon_Nr1.pdf"))
        assertTrue(fileNames.contains("Tankrechnung.pdf"))

        val contents = extractZipContents(zip)
        val documentXml = contents["document.xml"]!!

        // Z-Bon XML should link to ZBon PDF
        assertTrue(documentXml.contains("datafile=\"Kasse_20230112_1.xml\""))
        assertTrue(documentXml.contains("name=\"ZBon_Nr1.pdf\""))

        // Z-Bon XML should have 2 entries in one consolidate
        val zbonXml = contents["Kasse_20230112_1.xml"]!!
        assertEquals(2, "<cashLedger>".toRegex().findAll(zbonXml).count())

        // Standalone should have 1 entry
        val tankXml = contents["Kasse_20230112_2.xml"]!!
        assertEquals(1, "<cashLedger>".toRegex().findAll(tankXml).count())
    }

    @Test
    fun `buildExportPackage sequences across multiple dates`() {
        val entries =
            listOf(
                DatevXmlExportDto.CashLedgerEntryDto(
                    date = "2023-01-12",
                    amount = "100.00",
                    bookingText = "Day 1 Header 1",
                    headerId = 1,
                ),
                DatevXmlExportDto.CashLedgerEntryDto(
                    date = "2023-01-12",
                    amount = "200.00",
                    bookingText = "Day 1 Header 2",
                    headerId = 2,
                ),
                DatevXmlExportDto.CashLedgerEntryDto(
                    date = "2023-01-13",
                    amount = "300.00",
                    bookingText = "Day 2 Header 3",
                    headerId = 3,
                ),
            )

        val zip =
            DatevXmlExportBuilder.buildExportPackage(
                entries = entries,
                config = config,
                startDate = "2023-01-12",
                endDate = "2023-01-13",
            )

        val fileNames = extractZipFileNames(zip)
        assertTrue(fileNames.contains("Kasse_20230112_1.xml"), "day 1 header 1")
        assertTrue(fileNames.contains("Kasse_20230112_2.xml"), "day 1 header 2")
        assertTrue(fileNames.contains("Kasse_20230113_1.xml"), "day 2 header 1")
    }

    private fun extractZipFileNames(zipData: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(zipData)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                names.add(entry.name)
                entry = zis.nextEntry
            }
        }
        return names
    }

    private fun extractZipContents(zipData: ByteArray): Map<String, String> {
        val contents = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(zipData)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                contents[entry.name] = String(zis.readBytes(), Charsets.UTF_8)
                entry = zis.nextEntry
            }
        }
        return contents
    }
}
