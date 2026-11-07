/**
 * CSV exporter for DSFinV-K allocation_groups.csv (Bonkopf_AbrKreis).
 *
 * Generates the allocation_groups.csv file containing transaction-to-allocation
 * group mappings according to DSFinV-K specification. Links receipts to
 * specific tables or service areas (Abrechnungskreis).
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Exporter for allocation_groups.csv (Bonkopf_AbrKreis data).
 *
 * Maps transactions to their allocation groups (tables/service areas)
 * for proper revenue attribution and accounting.
 */
object AllocationGroupsCsvExporter {
    /**
     * CSV header for allocation_groups.csv per DSFinV-K specification.
     */
    private val HEADER =
        DsfinvkCsvUtils.joinRow(
            "Z_KASSE_ID",
            "Z_ERSTELLUNG",
            "Z_NR",
            "BON_ID",
            "ABRECHNUNGSKREIS",
        )

    /**
     * Generates allocation_groups.csv content.
     *
     * @param rows List of allocation group data
     * @return CSV content as ByteArray (UTF-8 encoded)
     */
    fun generateCsv(rows: List<DsfinvkExportDto.AllocationGroupRowDto>): ByteArray {
        if (rows.isEmpty()) return DsfinvkCsvUtils.buildCsv(HEADER, emptyList())

        val dataRows =
            rows.map { row ->
                DsfinvkCsvUtils.joinRow(
                    DsfinvkCsvUtils.escapeString(row.zKasseId),
                    DsfinvkCsvUtils.escapeString(row.zErstellung),
                    row.zNr.toString(),
                    DsfinvkCsvUtils.escapeString(row.bonId),
                    DsfinvkCsvUtils.escapeString(row.abrechnungskreis),
                )
            }

        return DsfinvkCsvUtils.buildCsv(HEADER, dataRows)
    }
}
