/**
 * CSV exporter for DSFinV-K lines.csv (Bonpos/line items).
 *
 * Generates the lines.csv file containing receipt line item data
 * according to DSFinV-K 2.4 specification.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Exporter for lines.csv (line item data).
 */
object LinesCsvExporter {
    /**
     * CSV header for lines.csv (Bonpos) per DSFinV-K 2.4 specification.
     */
    private val HEADER =
        DsfinvkCsvUtils.joinRow(
            "Z_KASSE_ID",
            "Z_ERSTELLUNG",
            "Z_NR",
            "BON_ID",
            "POS_ZEILE",
            "GUTSCHEIN_NR",
            "ARTIKELTEXT",
            "POS_TERMINAL_ID",
            "GV_TYP",
            "GV_NAME",
            "INHAUS",
            "P_STORNO",
            "AGENTUR_ID",
            "ART_NR",
            "GTIN",
            "WARENGR_ID",
            "WARENGR",
            "MENGE",
            "FAKTOR",
            "EINHEIT",
            "STK_BR",
        )

    /**
     * Generates lines.csv content using the new BonposRowDto.
     *
     * @param rows List of line item data
     * @return CSV content as ByteArray
     */
    fun generateCsv(rows: List<DsfinvkExportDto.BonposRowDto>): ByteArray {
        if (rows.isEmpty()) return DsfinvkCsvUtils.buildCsv(HEADER, emptyList())

        val dataRows =
            rows.map { row ->
                DsfinvkCsvUtils.joinRow(
                    DsfinvkCsvUtils.escapeString(row.zKasseId),
                    DsfinvkCsvUtils.escapeString(row.zErstellung),
                    row.zNr.toString(),
                    DsfinvkCsvUtils.escapeString(row.bonId),
                    DsfinvkCsvUtils.escapeString(row.posZeile),
                    DsfinvkCsvUtils.escapeString(row.gutscheinNr),
                    DsfinvkCsvUtils.escapeString(row.artikeltext),
                    DsfinvkCsvUtils.escapeString(row.posTerminalId),
                    DsfinvkCsvUtils.escapeString(row.gvTyp),
                    DsfinvkCsvUtils.escapeString(row.gvName),
                    DsfinvkCsvUtils.escapeString(row.inhaus),
                    DsfinvkCsvUtils.escapeString(row.pStorno),
                    // Anhang E: „Wird das Feld mit 0 gefüllt, werden die jeweiligen GV_TYPEN dem
                    // eigenen Unternehmen zugeordnet (=kein Agenturbezug)" (audit BPOS-9).
                    row.agenturId?.toString() ?: "0",
                    DsfinvkCsvUtils.escapeString(row.artNr),
                    DsfinvkCsvUtils.escapeString(row.gtin),
                    DsfinvkCsvUtils.escapeString(row.warengrId),
                    DsfinvkCsvUtils.escapeString(row.warengr),
                    DsfinvkCsvUtils.formatDecimal(row.menge),
                    DsfinvkCsvUtils.formatDecimal(row.faktor),
                    DsfinvkCsvUtils.escapeString(row.einheit),
                    DsfinvkCsvUtils.formatDecimal(row.stkBr),
                )
            }

        return DsfinvkCsvUtils.buildCsv(HEADER, dataRows)
    }
}
