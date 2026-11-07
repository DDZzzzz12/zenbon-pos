/**
 * CSV exporter for DSFinV-K cashpointclosing.csv (Stamm_Abschluss / closing master data).
 *
 * Generates the cashpointclosing.csv file containing cash closing data
 * with company information according to DSFinV-K specification 3.2.1.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Exporter for cashpointclosing.csv (Stamm_Abschluss / closing master data).
 */
object StammAbschlussCsvExporter {
    /**
     * CSV header for cashpointclosing.csv per DSFinV-K specification.
     */
    private val HEADER =
        DsfinvkCsvUtils.joinRow(
            "Z_KASSE_ID",
            "Z_ERSTELLUNG",
            "Z_NR",
            "Z_BUCHUNGSTAG",
            "TAXONOMIE_VERSION",
            "Z_START_ID",
            "Z_ENDE_ID",
            "NAME",
            "STRASSE",
            "PLZ",
            "ORT",
            "LAND",
            "STNR",
            "USTID",
            "Z_SE_ZAHLUNGEN",
            "Z_SE_BARZAHLUNGEN",
        )

    /**
     * Generates cashpointclosing.csv content.
     *
     * @param rows List of closing master data
     * @return CSV content as ByteArray (UTF-8 encoded)
     */
    fun generateCsv(rows: List<DsfinvkExportDto.StammAbschlussRowDto>): ByteArray {
        if (rows.isEmpty()) return DsfinvkCsvUtils.buildCsv(HEADER, emptyList())

        val dataRows =
            rows.map { row ->
                DsfinvkCsvUtils.joinRow(
                    DsfinvkCsvUtils.escapeString(row.zKasseId),
                    DsfinvkCsvUtils.escapeString(row.zErstellung),
                    row.zNr.toString(),
                    DsfinvkCsvUtils.escapeString(row.zBuchungstag),
                    DsfinvkCsvUtils.escapeString(row.taxonomieVersion),
                    DsfinvkCsvUtils.escapeString(row.zStartId),
                    DsfinvkCsvUtils.escapeString(row.zEndeId),
                    DsfinvkCsvUtils.escapeString(row.name),
                    DsfinvkCsvUtils.escapeString(row.strasse),
                    DsfinvkCsvUtils.escapeString(row.plz),
                    DsfinvkCsvUtils.escapeString(row.ort),
                    DsfinvkCsvUtils.escapeString(row.land),
                    DsfinvkCsvUtils.escapeString(row.stNr),
                    DsfinvkCsvUtils.escapeString(row.ustId),
                    DsfinvkCsvUtils.formatDecimal(row.zSeZahlungen),
                    DsfinvkCsvUtils.formatDecimal(row.zSeBarzahlungen),
                )
            }

        return DsfinvkCsvUtils.buildCsv(HEADER, dataRows)
    }
}
