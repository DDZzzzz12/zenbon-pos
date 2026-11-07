/**
 * CSV exporter for DSFinV-K lines_vat.csv (Bonpos_USt).
 *
 * Generates the lines_vat.csv file containing VAT breakdown data
 * per line item according to DSFinV-K 2.4 specification.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Exporter for lines_vat.csv (Bonpos_USt).
 */
object LinesVatCsvExporter {
    /**
     * CSV header for lines_vat.csv per DSFinV-K 2.4 specification.
     */
    private val HEADER =
        DsfinvkCsvUtils.joinRow(
            "Z_KASSE_ID",
            "Z_ERSTELLUNG",
            "Z_NR",
            "BON_ID",
            "POS_ZEILE",
            "UST_SCHLUESSEL",
            "POS_BRUTTO",
            "POS_NETTO",
            "POS_UST",
        )

    /**
     * Generates lines_vat.csv content using BonposUstRowDto.
     *
     * @param rows List of VAT breakdown data
     * @return CSV content as ByteArray
     */
    fun generateCsv(rows: List<DsfinvkExportDto.BonposUstRowDto>): ByteArray {
        if (rows.isEmpty()) return DsfinvkCsvUtils.buildCsv(HEADER, emptyList())

        val dataRows =
            rows.map { row ->
                DsfinvkCsvUtils.joinRow(
                    DsfinvkCsvUtils.escapeString(row.zKasseId),
                    DsfinvkCsvUtils.escapeString(row.zErstellung),
                    row.zNr.toString(),
                    DsfinvkCsvUtils.escapeString(row.bonId),
                    DsfinvkCsvUtils.escapeString(row.posZeile),
                    row.ustSchluessel.toString(),
                    DsfinvkCsvUtils.formatDecimal(row.posBrutto),
                    DsfinvkCsvUtils.formatDecimal(row.posNetto),
                    DsfinvkCsvUtils.formatDecimal(row.posUst),
                )
            }

        return DsfinvkCsvUtils.buildCsv(HEADER, dataRows)
    }
}
