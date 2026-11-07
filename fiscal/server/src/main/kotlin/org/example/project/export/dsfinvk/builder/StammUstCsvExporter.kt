/**
 * CSV exporter for DSFinV-K vat.csv (Stamm_USt / VAT rate master data).
 *
 * Generates the vat.csv file containing VAT rate definitions
 * according to DSFinV-K specification 3.2.6.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Exporter for vat.csv (Stamm_USt / VAT rate master data).
 */
object StammUstCsvExporter {
    /**
     * CSV header for vat.csv per DSFinV-K specification.
     */
    private val HEADER =
        DsfinvkCsvUtils.joinRow(
            "Z_KASSE_ID",
            "Z_ERSTELLUNG",
            "Z_NR",
            "UST_SCHLUESSEL",
            "UST_SATZ",
            "UST_BESCHR",
        )

    /**
     * Generates vat.csv content.
     *
     * @param rows List of VAT rate master data
     * @return CSV content as ByteArray (UTF-8 encoded)
     */
    fun generateCsv(rows: List<DsfinvkExportDto.StammUstRowDto>): ByteArray {
        if (rows.isEmpty()) return DsfinvkCsvUtils.buildCsv(HEADER, emptyList())

        val dataRows =
            rows.map { row ->
                DsfinvkCsvUtils.joinRow(
                    DsfinvkCsvUtils.escapeString(row.zKasseId),
                    DsfinvkCsvUtils.escapeString(row.zErstellung),
                    row.zNr.toString(),
                    row.ustSchluessel.toString(),
                    DsfinvkCsvUtils.formatDecimal(row.ustSatz),
                    DsfinvkCsvUtils.escapeString(row.ustBeschr),
                )
            }

        return DsfinvkCsvUtils.buildCsv(HEADER, dataRows)
    }
}
