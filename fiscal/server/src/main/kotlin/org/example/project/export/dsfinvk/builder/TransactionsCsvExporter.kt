/**
 * CSV exporter for DSFinV-K transactions.csv (Bonkopf/receipt headers).
 *
 * Generates the transactions.csv file containing transaction/receipt header
 * data according to DSFinV-K 2.4 specification.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Exporter for transactions.csv (receipt header data).
 */
object TransactionsCsvExporter {
    /**
     * CSV header for transactions.csv per DSFinV-K 2.4 specification.
     */
    private val HEADER =
        DsfinvkCsvUtils.joinRow(
            "Z_KASSE_ID",
            "Z_ERSTELLUNG",
            "Z_NR",
            "BON_ID",
            "BON_NR",
            "BON_TYP",
            "BON_NAME",
            "TERMINAL_ID",
            "BON_STORNO",
            "BON_START",
            "BON_ENDE",
            "BEDIENER_ID",
            "BEDIENER_NAME",
            "UMS_BRUTTO",
            "KUNDE_NAME",
            "KUNDE_ID",
            "KUNDE_TYP",
            "KUNDE_STRASSE",
            "KUNDE_PLZ",
            "KUNDE_ORT",
            "KUNDE_LAND",
            "KUNDE_USTID",
            "BON_NOTIZ",
        )

    /**
     * Generates transactions.csv content using BonkopfRowDto.
     *
     * @param rows List of transaction/receipt data
     * @return CSV content as ByteArray
     */
    fun generateCsv(rows: List<DsfinvkExportDto.BonkopfRowDto>): ByteArray {
        if (rows.isEmpty()) return DsfinvkCsvUtils.buildCsv(HEADER, emptyList())

        val dataRows =
            rows.map { row ->
                DsfinvkCsvUtils.joinRow(
                    DsfinvkCsvUtils.escapeString(row.zKasseId),
                    DsfinvkCsvUtils.escapeString(row.zErstellung),
                    row.zNr.toString(),
                    DsfinvkCsvUtils.escapeString(row.bonId),
                    row.bonNr.toString(),
                    DsfinvkCsvUtils.escapeString(row.bonTyp),
                    DsfinvkCsvUtils.escapeString(row.bonName),
                    DsfinvkCsvUtils.escapeString(row.terminalId),
                    DsfinvkCsvUtils.escapeString(row.bonStorno),
                    DsfinvkCsvUtils.escapeString(row.bonStart),
                    DsfinvkCsvUtils.escapeString(row.bonEnde),
                    DsfinvkCsvUtils.escapeString(row.bedienerId),
                    DsfinvkCsvUtils.escapeString(row.bedienerName),
                    DsfinvkCsvUtils.formatDecimal(row.umsBrutto),
                    DsfinvkCsvUtils.escapeString(row.kundeName),
                    DsfinvkCsvUtils.escapeString(row.kundeId),
                    DsfinvkCsvUtils.escapeString(row.kundeTyp),
                    DsfinvkCsvUtils.escapeString(row.kundeStrasse),
                    DsfinvkCsvUtils.escapeString(row.kundePlz),
                    DsfinvkCsvUtils.escapeString(row.kundeOrt),
                    DsfinvkCsvUtils.escapeString(row.kundeLand),
                    DsfinvkCsvUtils.escapeString(row.kundeUstId),
                    DsfinvkCsvUtils.escapeString(row.bonNotiz),
                )
            }

        return DsfinvkCsvUtils.buildCsv(HEADER, dataRows)
    }
}
