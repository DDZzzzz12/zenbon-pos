/**
 * CSV exporter for DSFinV-K location.csv (Stamm_Orte / location master data).
 *
 * Generates the location.csv file containing business location information
 * according to DSFinV-K specification 3.2.2.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Exporter for location.csv (Stamm_Orte / location master data).
 */
object StammOrteCsvExporter {
    /**
     * CSV header for location.csv per DSFinV-K specification.
     */
    private val HEADER =
        DsfinvkCsvUtils.joinRow(
            "Z_KASSE_ID",
            "Z_ERSTELLUNG",
            "Z_NR",
            "LOC_NAME",
            "LOC_STRASSE",
            "LOC_PLZ",
            "LOC_ORT",
            "LOC_LAND",
            "LOC_USTID",
        )

    /**
     * Generates location.csv content.
     *
     * @param rows List of location master data
     * @return CSV content as ByteArray (UTF-8 encoded)
     */
    fun generateCsv(rows: List<DsfinvkExportDto.StammOrteRowDto>): ByteArray {
        if (rows.isEmpty()) return DsfinvkCsvUtils.buildCsv(HEADER, emptyList())

        val dataRows =
            rows.map { row ->
                DsfinvkCsvUtils.joinRow(
                    DsfinvkCsvUtils.escapeString(row.zKasseId),
                    DsfinvkCsvUtils.escapeString(row.zErstellung),
                    row.zNr.toString(),
                    DsfinvkCsvUtils.escapeString(row.locName),
                    DsfinvkCsvUtils.escapeString(row.locStrasse),
                    DsfinvkCsvUtils.escapeString(row.locPlz),
                    DsfinvkCsvUtils.escapeString(row.locOrt),
                    DsfinvkCsvUtils.escapeString(row.locLand),
                    DsfinvkCsvUtils.escapeString(row.locUstId),
                )
            }

        return DsfinvkCsvUtils.buildCsv(HEADER, dataRows)
    }
}
