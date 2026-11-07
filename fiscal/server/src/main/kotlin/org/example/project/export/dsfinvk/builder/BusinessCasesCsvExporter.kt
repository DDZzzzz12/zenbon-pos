/**
 * CSV exporter for DSFinV-K businesscases.csv (Z_GV_Typ).
 *
 * Generates the businesscases.csv file containing aggregated business
 * case data according to DSFinV-K 2.4 specification.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Exporter for businesscases.csv (Z_GV_Typ).
 */
object BusinessCasesCsvExporter {
    /**
     * CSV header for businesscases.csv per DSFinV-K 2.4 specification.
     */
    private val HEADER =
        DsfinvkCsvUtils.joinRow(
            "Z_KASSE_ID",
            "Z_ERSTELLUNG",
            "Z_NR",
            "GV_TYP",
            "GV_NAME",
            "AGENTUR_ID",
            "UST_SCHLUESSEL",
            "Z_UMS_BRUTTO",
            "Z_UMS_NETTO",
            "Z_UST",
        )

    /**
     * Generates businesscases.csv content using ZGvTypRowDto.
     *
     * @param rows List of business case summary data
     * @return CSV content as ByteArray
     */
    fun generateCsv(rows: List<DsfinvkExportDto.ZGvTypRowDto>): ByteArray {
        if (rows.isEmpty()) return DsfinvkCsvUtils.buildCsv(HEADER, emptyList())

        val dataRows =
            rows.map { row ->
                DsfinvkCsvUtils.joinRow(
                    DsfinvkCsvUtils.escapeString(row.zKasseId),
                    DsfinvkCsvUtils.escapeString(row.zErstellung),
                    row.zNr.toString(),
                    DsfinvkCsvUtils.escapeString(row.gvTyp),
                    DsfinvkCsvUtils.escapeString(row.gvName),
                    // Anhang E: 0 = kein Agenturbezug (audit KABS-11).
                    row.agenturId?.toString() ?: "0",
                    row.ustSchluessel.toString(),
                    DsfinvkCsvUtils.formatDecimal(row.zUmsBrutto),
                    DsfinvkCsvUtils.formatDecimal(row.zUmsNetto),
                    DsfinvkCsvUtils.formatDecimal(row.zUst),
                )
            }

        return DsfinvkCsvUtils.buildCsv(HEADER, dataRows)
    }
}
