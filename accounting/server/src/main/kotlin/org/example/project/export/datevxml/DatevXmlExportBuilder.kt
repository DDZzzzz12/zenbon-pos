/**
 * Orchestrates DATEV XML Schnittstelle online export ZIP generation.
 *
 * Takes a list of cash ledger entries and export configuration,
 * groups entries by Kassenbuch header (so Z-Bon VAT lines stay together),
 * generates one ledger XML per header and a document.xml manifest,
 * then packages everything into a ZIP archive.
 *
 * The resulting ZIP structure (hybrid einzelDateien/GesamtDatei):
 * ```
 * export.zip
 * ├── document.xml              (archive manifest)
 * ├── Kasse_20260101_1.xml      (Z-Bon: 4 entries in one consolidate)
 * ├── Kasse_20260102_1.xml      (standalone manual entry)
 * ├── Kasse_20260102_2.xml      (standalone manual entry)
 * └── ...
 * ```
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.datevxml

import org.example.project.core.domain.dto.datevxml.DatevXmlExportDto
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builder for DATEV XML Schnittstelle online export packages.
 *
 * Coordinates XML generation and ZIP packaging following the DATEV
 * Document-Package specification v6.0.
 */
object DatevXmlExportBuilder {
    /**
     * Builds a complete DATEV XML export package as a ZIP archive.
     *
     * Groups entries by date, generates a ledger XML per day,
     * creates the document.xml manifest, and packages everything
     * including linked PDF documents into a ZIP archive.
     *
     * @param entries All cash ledger entries for the export period
     * @param config Export configuration (account, name, shop info)
     * @param startDate Export period start date (YYYY-MM-DD)
     * @param endDate Export period end date (YYYY-MM-DD)
     * @param pdfAttachments PDF documents linked to Kassenbuch entries
     * @return ZIP archive as ByteArray
     */
    fun buildExportPackage(
        entries: List<DatevXmlExportDto.CashLedgerEntryDto>,
        config: DatevXmlExportDto.ExportConfigDto,
        startDate: String,
        endDate: String,
        pdfAttachments: List<PdfAttachment> = emptyList(),
    ): ByteArray {
        val ledgerFiles = mutableMapOf<String, ByteArray>()
        val pdfFileNamesByLedger = mutableMapOf<String, String>()

        // Group entries by headerId, then by date for sequencing
        // Entries with headerId=0 are treated as individual (legacy/test data)
        val entriesByHeader = entries.groupBy { it.headerId }
        val headerGroups = entriesByHeader.values.toList()

        // Sort groups by date then headerId for stable ordering
        val sortedGroups = headerGroups.sortedWith(compareBy({ it.first().date }, { it.first().headerId }))

        // Track per-date sequence counters
        val dateSequence = mutableMapOf<String, Int>()

        for (group in sortedGroups) {
            val date = group.first().date
            val compactDate = date.replace("-", "")
            val sequence = (dateSequence[date] ?: 0) + 1
            dateSequence[date] = sequence
            val fileName = "Kasse_${compactDate}_$sequence.xml"

            // Generate ledger XML with all entries in one consolidate
            val ledgerXml =
                DatevXmlLedgerGenerator.generate(
                    entries = group,
                    date = date,
                    generatorInfo = config.clientName,
                    generatingSystem = config.generatingSystem,
                )
            ledgerFiles[fileName] = ledgerXml

            // Link PDF: take the first non-null linkedPdfFileName from the group
            val linkedPdf = group.firstNotNullOfOrNull { it.linkedPdfFileName }
            if (linkedPdf != null) {
                pdfFileNamesByLedger[fileName] = linkedPdf
            }
        }

        // Generate document.xml with per-header PDF linking
        val documentXml =
            DatevXmlDocumentGenerator.generate(
                ledgerFileNames = ledgerFiles.keys.toList().sorted(),
                config = config,
                startDate = startDate,
                endDate = endDate,
                pdfFileNamesByLedger = pdfFileNamesByLedger,
            )

        // Collect PDF file data for ZIP inclusion
        val pdfFiles = pdfAttachments.associate { it.fileName to it.data }

        // Package into ZIP
        return createZip(documentXml, ledgerFiles, pdfFiles)
    }

    /**
     * Creates the final ZIP archive containing document.xml, ledger files, and PDF attachments.
     *
     * @param documentXml The document.xml manifest content
     * @param ledgerFiles Map of filename -> XML content for each day
     * @param pdfFiles Map of filename -> PDF bytes for linked documents
     * @return ZIP archive as ByteArray
     */
    private fun createZip(
        documentXml: ByteArray,
        ledgerFiles: Map<String, ByteArray>,
        pdfFiles: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val outputStream = ByteArrayOutputStream()
        ZipOutputStream(outputStream).use { zip ->
            // Add document.xml
            zip.putNextEntry(ZipEntry("document.xml"))
            zip.write(documentXml)
            zip.closeEntry()

            // Add per-day ledger files
            for ((fileName, content) in ledgerFiles.toSortedMap()) {
                zip.putNextEntry(ZipEntry(fileName))
                zip.write(content)
                zip.closeEntry()
            }

            // Add linked PDF documents
            for ((fileName, content) in pdfFiles.toSortedMap()) {
                zip.putNextEntry(ZipEntry(fileName))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return outputStream.toByteArray()
    }
}
