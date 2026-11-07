/**
 * CSV exporter for DSFinV-K cashregister.csv (Stamm_Kassen / cash register master data).
 *
 * Generates the cashregister.csv file containing cash register/POS information
 * according to DSFinV-K specification 3.2.3.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Exporter for cashregister.csv (Stamm_Kassen / cash register master data).
 */
object StammKassenCsvExporter {
    /**
     * CSV header for cashregister.csv per DSFinV-K specification.
     */
    private val HEADER =
        DsfinvkCsvUtils.joinRow(
            "Z_KASSE_ID",
            "Z_ERSTELLUNG",
            "Z_NR",
            "KASSE_BRAND",
            "KASSE_MODELL",
            "KASSE_SERIENNR",
            "KASSE_SW_BRAND",
            "KASSE_SW_VERSION",
            "KASSE_BASISWAEH_CODE",
            "KEINE_UST_ZUORDNUNG",
        )

    /**
     * Generates cashregister.csv content.
     *
     * @param rows List of cash register master data
     * @return CSV content as ByteArray (UTF-8 encoded)
     */
    fun generateCsv(rows: List<DsfinvkExportDto.StammKassenRowDto>): ByteArray {
        if (rows.isEmpty()) return DsfinvkCsvUtils.buildCsv(HEADER, emptyList())

        val dataRows =
            rows.map { row ->
                DsfinvkCsvUtils.joinRow(
                    DsfinvkCsvUtils.escapeString(row.zKasseId),
                    DsfinvkCsvUtils.escapeString(row.zErstellung),
                    row.zNr.toString(),
                    DsfinvkCsvUtils.escapeString(row.kasseBrand),
                    DsfinvkCsvUtils.escapeString(row.kasseModell),
                    DsfinvkCsvUtils.escapeString(row.kasseSerienNr),
                    DsfinvkCsvUtils.escapeString(row.kasseSwBrand),
                    DsfinvkCsvUtils.escapeString(row.kasseSwVersion),
                    DsfinvkCsvUtils.escapeString(row.kasseBasiswaehCode),
                    DsfinvkCsvUtils.escapeString(row.keineUstZuordnung),
                )
            }

        return DsfinvkCsvUtils.buildCsv(HEADER, dataRows)
    }
}
