/**
 * Orchestrates DATEV XML Eingangsrechnungen (Accounts Payable) export ZIP generation.
 *
 * Takes a flat list of AP ledger entries (which may contain multiple entries per invoice
 * for split-tax invoices) and a map of invoiceId→PdfAttachment for direct PDF lookup,
 * groups entries by invoiceId, generates per-invoice ledger XML files and a document.xml
 * manifest, then packages everything into a ZIP archive.
 *
 * The resulting ZIP structure:
 * ```
 * export.zip
 * ├── document.xml                        (archive manifest)
 * ├── Rechnungsdaten_RE_INV001.xml        (ledger for invoice INV001)
 * ├── Rechnungsdaten_RE_INV002.xml        (ledger for invoice INV002)
 * ├── Invoice_INV001.pdf                  (invoice PDF)
 * ├── Invoice_INV002.pdf                  (invoice PDF)
 * └── ...
 * ```
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.datevxml.ap

import org.example.project.core.domain.dto.datevxml.DatevApExportDto
import org.example.project.export.datevxml.PdfAttachment
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builder for DATEV XML Eingangsrechnungen export packages.
 *
 * Coordinates XML generation and ZIP packaging following the DATEV
 * Document-Package specification v6.0 for accountsPayableLedger type.
 */
object DatevApExportBuilder {
    /**
     * Builds a complete DATEV AP export package as a ZIP archive.
     *
     * Groups entries by invoiceId so that split-tax invoices produce a single
     * ledger XML file with multiple `<accountsPayableLedger>` elements inside
     * one `<consolidate>` block. Single-tax invoices produce one entry per consolidate.
     *
     * PDF matching uses a direct invoiceId→PdfAttachment map built by the service layer,
     * which knows the invoiceId↔Document relationship from InvoiceMetadata.
     *
     * @param entries AP ledger entries (may be multiple per invoice for split-tax)
     * @param config Export configuration (client info)
     * @param startDate Export period start date (YYYY-MM-DD)
     * @param endDate Export period end date (YYYY-MM-DD)
     * @param pdfByInvoiceId Map of sanitized invoiceId to PdfAttachment for direct lookup
     * @return ZIP archive as ByteArray
     */
    fun buildExportPackage(
        entries: List<DatevApExportDto.ApLedgerEntryDto>,
        config: DatevApExportDto.ApExportConfigDto,
        startDate: String,
        endDate: String,
        pdfByInvoiceId: Map<String, PdfAttachment> = emptyMap(),
    ): ByteArray {
        // Group entries by invoiceId for split-tax support
        val entriesByInvoice = entries.groupBy { it.invoiceId }

        // Generate per-invoice ledger XML files
        val ledgerFiles = mutableMapOf<String, ByteArray>()
        val documentEntries = mutableListOf<DatevApDocumentGenerator.ApDocumentEntry>()

        for ((invoiceId, invoiceEntries) in entriesByInvoice) {
            val ledgerFileName = generateLedgerFileName(invoiceId)

            // Use date and deliveryDate from the first entry (shared across splits)
            val invoiceDate = invoiceEntries.first().date
            val deliveryDate = invoiceEntries.first().deliveryDate

            val ledgerXml =
                DatevApLedgerGenerator.generate(
                    entries = invoiceEntries,
                    invoiceId = invoiceId,
                    invoiceDate = invoiceDate,
                    generatorInfo = config.clientName,
                    generatingSystem = config.generatingSystem,
                    deliveryDate = deliveryDate,
                )
            ledgerFiles[ledgerFileName] = ledgerXml

            // Direct lookup by invoiceId — no filename heuristics
            val pdfAttachment = pdfByInvoiceId[invoiceId]

            documentEntries.add(
                DatevApDocumentGenerator.ApDocumentEntry(
                    ledgerFileName = ledgerFileName,
                    invoiceDate = invoiceDate,
                    pdfFileName = pdfAttachment?.fileName,
                ),
            )
        }

        // Generate document.xml referencing all ledger files and PDFs
        val documentXml =
            DatevApDocumentGenerator.generate(
                entries = documentEntries,
                config = config,
                startDate = startDate,
                endDate = endDate,
            )

        // Collect PDF file data for ZIP inclusion from the map values
        val pdfFiles = pdfByInvoiceId.values.associate { it.fileName to it.data }

        // Package into ZIP
        return createZip(documentXml, ledgerFiles, pdfFiles)
    }

    /**
     * Generates a ledger filename from an invoice ID.
     *
     * Sanitizes the invoice ID by replacing non-alphanumeric characters with underscores.
     *
     * @param invoiceId Invoice number/ID
     * @return Filename like "Rechnungsdaten_RE_INV001.xml"
     */
    private fun generateLedgerFileName(invoiceId: String): String {
        val sanitized = invoiceId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "Rechnungsdaten_RE_$sanitized.xml"
    }

    /**
     * Creates the final ZIP archive containing document.xml, ledger files, and PDF attachments.
     *
     * @param documentXml The document.xml manifest content
     * @param ledgerFiles Map of filename -> XML content for each invoice
     * @param pdfFiles Map of filename -> PDF bytes for invoice documents
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

            // Add per-invoice ledger files
            for ((fileName, content) in ledgerFiles.toSortedMap()) {
                zip.putNextEntry(ZipEntry(fileName))
                zip.write(content)
                zip.closeEntry()
            }

            // Add invoice PDF documents
            for ((fileName, content) in pdfFiles.toSortedMap()) {
                zip.putNextEntry(ZipEntry(fileName))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return outputStream.toByteArray()
    }
}
