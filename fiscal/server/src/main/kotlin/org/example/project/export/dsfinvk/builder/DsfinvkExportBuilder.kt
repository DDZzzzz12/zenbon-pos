/**
 * Main orchestrator for DSFinV-K CSV export generation.
 *
 * Coordinates the generation of all required CSV files according to DSFinV-K
 * specification and packages them into a ZIP archive for download.
 *
 * DSFinV-K (Digitale Schnittstelle der Finanzverwaltung für Kassensysteme) is
 * the German standard for digital interfaces in cash register systems.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.export.dsfinvk.model.StammTseCertificate
import org.example.project.export.dsfinvk.model.TseTarFile
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * DSFinV-K export builder for generating fiscal compliance CSV exports.
 *
 * Creates all required CSV files and packages them into a ZIP archive
 * following the official DSFinV-K specification for German fiscal audits.
 */
object DsfinvkExportBuilder {
    /**
     * Directory name for DSFinV-K files within the ZIP archive.
     */
    private const val DSFINVK_DIR = "dsfinvk/"

    /**
     * Directory name for TSE TAR export files within the ZIP archive.
     */
    private const val TSE_DIR = "tse/"

    /**
     * Builds a complete DSFinV-K 2.4 export package as a ZIP archive.
     *
     * @param transactions List of transaction/receipt data (BonkopfRowDto)
     * @param transactionsTse List of TSE signature data (TseTransaktionenRowDto)
     * @param lineItems List of line item data (BonposRowDto)
     * @param lineVats List of VAT breakdown per line (BonposUstRowDto)
     * @param dataPayments List of individual payment data (BonkopfZahlartenRowDto)
     * @param businessCases List of business case summaries (ZGvTypRowDto)
     * @param paymentSummaries List of payment aggregates per Z-Bon (ZZahlartRowDto)
     * @param cashPerCurrency List of currency totals (ZWaehrungenRowDto)
     * @param allocationGroups List of allocation group/table mappings (Bonkopf_AbrKreis)
     * @param references List of transaction references (Bon_Referenzen)
     * @param itemAmounts List of price finding/breakdown data (Bonpos_Preisfindung)
     * @param subItems List of sub-items/extras data (Bonpos_Zusatzinfo)
     * @param bonkopfUst List of VAT per receipt header (Bonkopf_USt)
     * @param stammAbschluss List of closing master data (Stamm_Abschluss)
     * @param stammOrte List of location master data (Stamm_Orte)
     * @param stammKassen List of cash register master data (Stamm_Kassen)
     * @param stammUst List of VAT rate master data (Stamm_USt)
     * @param stammTse List of TSE master data (Stamm_TSE)
     * @param stammTerminals List of terminal master data (Stamm_Terminals)
     * @param shopName Shop name for index.xml
     * @param startDate Export period start date
     * @param endDate Export period end date
     * @param tseTarData Optional TSE TAR export data to include in the package
     * @param tseTarFileName Optional name for the TSE TAR file (default: tse_export.tar)
     * @param additionalTseTars Further TARs shipped beside it in `tse/` — the registered
     *   export-and-delete archives that hold the period's entries no longer on the device. A TSE
     *   export may be delivered as several TAR files; each keeps its own name.
     * @return ByteArray containing the ZIP archive
     */
    fun buildExportPackage(
        transactions: List<DsfinvkExportDto.BonkopfRowDto>,
        transactionsTse: List<DsfinvkExportDto.TseTransaktionenRowDto>,
        lineItems: List<DsfinvkExportDto.BonposRowDto>,
        lineVats: List<DsfinvkExportDto.BonposUstRowDto>,
        dataPayments: List<DsfinvkExportDto.BonkopfZahlartenRowDto>,
        businessCases: List<DsfinvkExportDto.ZGvTypRowDto>,
        paymentSummaries: List<DsfinvkExportDto.ZZahlartRowDto>,
        cashPerCurrency: List<DsfinvkExportDto.ZWaehrungenRowDto>,
        allocationGroups: List<DsfinvkExportDto.AllocationGroupRowDto> = emptyList(),
        references: List<DsfinvkExportDto.ReferenceRowDto> = emptyList(),
        itemAmounts: List<DsfinvkExportDto.PriceFindingRowDto> = emptyList(),
        subItems: List<DsfinvkExportDto.SubItemRowDto> = emptyList(),
        bonkopfUst: List<DsfinvkExportDto.BonkopfUstRowDto> = emptyList(),
        stammAbschluss: List<DsfinvkExportDto.StammAbschlussRowDto> = emptyList(),
        stammOrte: List<DsfinvkExportDto.StammOrteRowDto> = emptyList(),
        stammKassen: List<DsfinvkExportDto.StammKassenRowDto> = emptyList(),
        stammUst: List<DsfinvkExportDto.StammUstRowDto> = emptyList(),
        stammTse: List<DsfinvkExportDto.StammTseRowDto> = emptyList(),
        stammTerminals: List<DsfinvkExportDto.StammTerminalsRowDto> = emptyList(),
        shopName: String,
        startDate: String,
        endDate: String,
        tseTarData: ByteArray? = null,
        tseTarFileName: String? = null,
        additionalTseTars: List<TseTarFile> = emptyList(),
    ): ByteArray {
        val outputStream = ByteArrayOutputStream()
        ZipOutputStream(outputStream).use { zip ->
            // ============================================
            // Einzelaufzeichnungsmodul (Individual Records)
            // ============================================

            // Bonpos (lines.csv)
            addCsvToZip(
                zip,
                "lines.csv",
                LinesCsvExporter.generateCsv(lineItems),
            )

            // Bonpos_USt (lines_vat.csv)
            addCsvToZip(
                zip,
                "lines_vat.csv",
                LinesVatCsvExporter.generateCsv(lineVats),
            )

            // Bonpos_Preisfindung (itemamounts.csv)
            addCsvToZip(
                zip,
                "itemamounts.csv",
                ItemAmountsCsvExporter.generateCsv(itemAmounts),
            )

            // Bonpos_Zusatzinfo (subitems.csv)
            addCsvToZip(
                zip,
                "subitems.csv",
                SubItemsCsvExporter.generateCsv(subItems),
            )

            // Bonkopf (transactions.csv)
            addCsvToZip(
                zip,
                "transactions.csv",
                TransactionsCsvExporter.generateCsv(transactions),
            )

            // Bonkopf_USt (transactions_vat.csv)
            addCsvToZip(
                zip,
                "transactions_vat.csv",
                BonkopfUstCsvExporter.generateCsv(bonkopfUst),
            )

            // Bonkopf_AbrKreis (allocation_groups.csv)
            addCsvToZip(
                zip,
                "allocation_groups.csv",
                AllocationGroupsCsvExporter.generateCsv(allocationGroups),
            )

            // Bonkopf_Zahlarten (datapayment.csv)
            addCsvToZip(
                zip,
                "datapayment.csv",
                DataPaymentCsvExporter.generateCsv(dataPayments),
            )

            // Bon_Referenzen (references.csv)
            addCsvToZip(
                zip,
                "references.csv",
                ReferencesCsvExporter.generateCsv(references),
            )

            // TSE_Transaktionen (transactions_tse.csv)
            addCsvToZip(
                zip,
                "transactions_tse.csv",
                TransactionsTseCsvExporter.generateCsv(transactionsTse),
            )

            // ============================================
            // Stammdatenmodul (Master Data)
            // ============================================

            // Stamm_Abschluss (cashpointclosing.csv)
            addCsvToZip(
                zip,
                "cashpointclosing.csv",
                StammAbschlussCsvExporter.generateCsv(stammAbschluss),
            )

            // Stamm_Orte (location.csv)
            addCsvToZip(
                zip,
                "location.csv",
                StammOrteCsvExporter.generateCsv(stammOrte),
            )

            // Stamm_Kassen (cashregister.csv)
            addCsvToZip(
                zip,
                "cashregister.csv",
                StammKassenCsvExporter.generateCsv(stammKassen),
            )

            // Stamm_USt (vat.csv)
            addCsvToZip(
                zip,
                "vat.csv",
                StammUstCsvExporter.generateCsv(stammUst),
            )

            // Stamm_TSE (tse.csv)
            addCsvToZip(
                zip,
                "tse.csv",
                StammTseCsvExporter.generateCsv(stammTse),
            )

            // Stamm_Terminals (slaves.csv)
            addCsvToZip(
                zip,
                "slaves.csv",
                StammTerminalsCsvExporter.generateCsv(stammTerminals),
            )

            // Stamm_Agenturen (pa.csv) - always included, header-only when empty
            zip.putNextEntry(ZipEntry("${DSFINVK_DIR}pa.csv"))
            zip.write(StammAgenturenCsvExporter.generateCsv())
            zip.closeEntry()

            // ============================================
            // Kassenabschlussmodul (Cash Closing)
            // ============================================

            // Z_GV_Typ (businesscases.csv)
            addCsvToZip(
                zip,
                "businesscases.csv",
                BusinessCasesCsvExporter.generateCsv(businessCases),
            )

            // Z_Zahlart (payment.csv)
            addCsvToZip(
                zip,
                "payment.csv",
                PaymentSummaryCsvExporter.generateCsv(paymentSummaries),
            )

            // Z_Waehrungen (cash_per_currency.csv)
            addCsvToZip(
                zip,
                "cash_per_currency.csv",
                CashPerCurrencyCsvExporter.generateCsv(cashPerCurrency),
            )

            // Generate and add index.xml. The Stamm_TSE TSE_ZERTIFIKAT_* count is derived from the
            // SAME stammTse rows StammTseCsvExporter wrote above, through the SAME shared helper, so
            // tse.csv and the descriptor declare an identical column set (audit R2).
            addXmlToZip(
                zip,
                "index.xml",
                DsfinvkIndexXmlGenerator.generateIndexXml(
                    shopName = shopName,
                    startDate = startDate,
                    endDate = endDate,
                    transactionCount = transactions.size,
                    zBonCount = stammAbschluss.size,
                    certificateFieldCount = StammTseCertificate.fieldCount(stammTse),
                ),
            )

            // Ship the GDPdU DTD next to index.xml — its DOCTYPE references the file by a
            // relative SYSTEM id, and the official v2.4 package delivers it on the medium
            // (audit CONT-4). Byte-identical copy of the official file.
            val dtd =
                DsfinvkExportBuilder::class.java
                    .getResourceAsStream("/dsfinvk/gdpdu-01-09-2004.dtd")
                    ?.readBytes()
                    ?: error("bundled gdpdu-01-09-2004.dtd resource missing")
            addXmlToZip(zip, "gdpdu-01-09-2004.dtd", dtd)

            // Add TSE TAR export if provided
            if (tseTarData != null && tseTarData.isNotEmpty()) {
                val deviceName = tseTarFileName ?: "tse_export.tar"
                addTseTarToZip(zip, deviceName, tseTarData)
                val taken = mutableSetOf(deviceName)
                additionalTseTars.forEach { tar ->
                    // A ZIP may not hold one name twice; an archive that happens to share the
                    // device export's name keeps its content under a prefixed one.
                    val name = if (taken.add(tar.fileName)) tar.fileName else "archive_${taken.size}_${tar.fileName}".also(taken::add)
                    addTseTarToZip(zip, name, tar.bytes)
                }
            }
        }
        return outputStream.toByteArray()
    }

    /**
     * Adds a CSV file to the ZIP archive.
     *
     * Every DSFinV-K CSV described by `index.xml` must be present in the export, header-only
     * when its underlying data is empty (H8) — so this is unconditional; exporters are
     * responsible for returning at least the header row, never `ByteArray(0)`.
     *
     * @param zip ZipOutputStream to add the file to
     * @param fileName Name of the CSV file
     * @param content CSV content as ByteArray
     */
    private fun addCsvToZip(
        zip: ZipOutputStream,
        fileName: String,
        content: ByteArray,
    ) {
        zip.putNextEntry(ZipEntry("$DSFINVK_DIR$fileName"))
        zip.write(content)
        zip.closeEntry()
    }

    /**
     * Adds an XML file to the ZIP archive.
     *
     * @param zip ZipOutputStream to add the file to
     * @param fileName Name of the XML file
     * @param content XML content as ByteArray
     */
    private fun addXmlToZip(
        zip: ZipOutputStream,
        fileName: String,
        content: ByteArray,
    ) {
        zip.putNextEntry(ZipEntry("$DSFINVK_DIR$fileName"))
        zip.write(content)
        zip.closeEntry()
    }

    /**
     * Adds a TSE TAR export file to the ZIP archive.
     *
     * The TSE TAR is placed in the tse/ directory within the ZIP archive.
     * This provides the cryptographic signature data required for DSFinV-K compliance.
     *
     * @param zip ZipOutputStream to add the file to
     * @param fileName Name of the TAR file
     * @param content TAR file content as ByteArray
     */
    private fun addTseTarToZip(
        zip: ZipOutputStream,
        fileName: String,
        content: ByteArray,
    ) {
        zip.putNextEntry(ZipEntry("$TSE_DIR$fileName"))
        zip.write(content)
        zip.closeEntry()
    }

    /**
     * Gets the list of files included in a DSFinV-K export.
     *
     * @return List of file names in the export
     */
    fun getExportFileNames(): List<String> =
        listOf(
            // Einzelaufzeichnungsmodul
            "lines.csv", // Bonpos
            "lines_vat.csv", // Bonpos_USt
            "itemamounts.csv", // Bonpos_Preisfindung
            "subitems.csv", // Bonpos_Zusatzinfo
            "transactions.csv", // Bonkopf
            "transactions_vat.csv", // Bonkopf_USt
            "allocation_groups.csv", // Bonkopf_AbrKreis
            "datapayment.csv", // Bonkopf_Zahlarten
            "references.csv", // Bon_Referenzen
            "transactions_tse.csv", // TSE_Transaktionen
            // Stammdatenmodul
            "cashpointclosing.csv", // Stamm_Abschluss
            "location.csv", // Stamm_Orte
            "cashregister.csv", // Stamm_Kassen
            "vat.csv", // Stamm_USt
            "tse.csv", // Stamm_TSE
            "slaves.csv", // Stamm_Terminals
            "pa.csv", // Stamm_Agenturen
            // Kassenabschlussmodul
            "businesscases.csv", // Z_GV_Typ
            "payment.csv", // Z_Zahlart
            "cash_per_currency.csv", // Z_Waehrungen
            // Descriptor
            "index.xml",
            // m18: the GDPdU DTD ships next to index.xml (its DOCTYPE references it by name), so it
            // belongs in the completeness list — otherwise a broken DTD-bundling step would go
            // unnoticed by DsfinvkEmptyFilesTest, which asserts every name here is present in the ZIP.
            "gdpdu-01-09-2004.dtd",
        )
}
