/**
 * CSV exporter for slaves.csv (Stamm_Terminals).
 *
 * Generates DSFinV-K compliant CSV for terminal master data export.
 * Required for multi-client POS architecture compliance.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Exporter for slaves.csv (Stamm_Terminals).
 *
 * Generates CSV rows for terminal master data per DSFinV-K spec.
 */
object StammTerminalsCsvExporter {
    /**
     * CSV header row for stamm_terminals.csv.
     *
     * Columns per DSFinV-K Stamm_Terminals specification:
     * - Z_KASSE_ID: Cash register ID
     * - Z_ERSTELLUNG: Z-report creation timestamp
     * - Z_NR: Z-report number
     * - TERMINAL_ID: Terminal identifier
     * - TERMINAL_BRAND: Terminal manufacturer/brand
     * - TERMINAL_MODELL: Terminal model (German spelling per DSFinV-K spec)
     * - TERMINAL_SERIAL_NR: Terminal serial number
     * - TERMINAL_SW_BRAND: Terminal software brand
     * - TERMINAL_SW_VERSION: Terminal software version
     */
    private val HEADER =
        DsfinvkCsvUtils.joinRow(
            "Z_KASSE_ID",
            "Z_ERSTELLUNG",
            "Z_NR",
            "TERMINAL_ID",
            "TERMINAL_BRAND",
            "TERMINAL_MODELL",
            "TERMINAL_SERIENNR",
            "TERMINAL_SW_BRAND",
            "TERMINAL_SW_VERSION",
        )

    /**
     * Generates slaves.csv content from row DTOs.
     *
     * @param rows List of StammTerminalsRowDto objects
     * @return CSV content as ByteArray (UTF-8 encoded)
     */
    fun generateCsv(rows: List<DsfinvkExportDto.StammTerminalsRowDto>): ByteArray {
        if (rows.isEmpty()) {
            return DsfinvkCsvUtils.buildCsv(HEADER, emptyList())
        }

        val dataRows =
            rows.map { row ->
                DsfinvkCsvUtils.joinRow(
                    DsfinvkCsvUtils.escapeString(row.zKasseId),
                    DsfinvkCsvUtils.escapeString(row.zErstellung),
                    row.zNr.toString(),
                    DsfinvkCsvUtils.escapeString(row.terminalId),
                    DsfinvkCsvUtils.escapeString(row.terminalBrand),
                    DsfinvkCsvUtils.escapeString(row.terminalModel),
                    DsfinvkCsvUtils.escapeString(row.terminalSerialNr),
                    DsfinvkCsvUtils.escapeString(row.terminalSwBrand),
                    DsfinvkCsvUtils.escapeString(row.terminalSwVersion),
                )
            }

        return DsfinvkCsvUtils.buildCsv(HEADER, dataRows)
    }
}
