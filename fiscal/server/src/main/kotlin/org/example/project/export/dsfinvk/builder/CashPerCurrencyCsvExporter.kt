/**
 * CSV exporter for DSFinV-K cash_per_currency.csv (Z_Waehrungen).
 *
 * Generates the cash_per_currency.csv file containing cash totals
 * per currency according to DSFinV-K 2.4 specification.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Exporter for cash_per_currency.csv (Z_Waehrungen).
 */
object CashPerCurrencyCsvExporter {
    /**
     * CSV header for cash_per_currency.csv per DSFinV-K 2.4 specification.
     */
    private val HEADER =
        DsfinvkCsvUtils.joinRow(
            "Z_KASSE_ID",
            "Z_ERSTELLUNG",
            "Z_NR",
            "ZAHLART_WAEH",
            "ZAHLART_BETRAG_WAEH",
        )

    /**
     * Generates cash_per_currency.csv content using ZWaehrungenRowDto.
     *
     * @param rows List of currency total data
     * @return CSV content as ByteArray
     */
    fun generateCsv(rows: List<DsfinvkExportDto.ZWaehrungenRowDto>): ByteArray {
        if (rows.isEmpty()) return DsfinvkCsvUtils.buildCsv(HEADER, emptyList())

        val dataRows =
            rows.map { row ->
                DsfinvkCsvUtils.joinRow(
                    DsfinvkCsvUtils.escapeString(row.zKasseId),
                    DsfinvkCsvUtils.escapeString(row.zErstellung),
                    row.zNr.toString(),
                    DsfinvkCsvUtils.escapeString(row.zahlartWaeh),
                    DsfinvkCsvUtils.formatDecimal(row.zahlartBetragWaeh),
                )
            }

        return DsfinvkCsvUtils.buildCsv(HEADER, dataRows)
    }
}
