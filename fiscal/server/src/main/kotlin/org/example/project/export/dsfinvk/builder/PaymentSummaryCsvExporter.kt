/**
 * CSV exporter for DSFinV-K payment.csv (Z_Zahlart).
 *
 * Generates the payment.csv file containing aggregated payment data
 * per Z-report according to DSFinV-K 2.4 specification.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Exporter for payment.csv (Z_Zahlart).
 */
object PaymentSummaryCsvExporter {
    /**
     * CSV header for payment.csv per DSFinV-K 2.4 specification.
     */
    private val HEADER =
        DsfinvkCsvUtils.joinRow(
            "Z_KASSE_ID",
            "Z_ERSTELLUNG",
            "Z_NR",
            "ZAHLART_TYP",
            "ZAHLART_NAME",
            "Z_ZAHLART_BETRAG",
        )

    /**
     * Generates payment.csv content using ZZahlartRowDto.
     *
     * @param rows List of payment summary data
     * @return CSV content as ByteArray
     */
    fun generateCsv(rows: List<DsfinvkExportDto.ZZahlartRowDto>): ByteArray {
        if (rows.isEmpty()) return DsfinvkCsvUtils.buildCsv(HEADER, emptyList())

        val dataRows =
            rows.map { row ->
                DsfinvkCsvUtils.joinRow(
                    DsfinvkCsvUtils.escapeString(row.zKasseId),
                    DsfinvkCsvUtils.escapeString(row.zErstellung),
                    row.zNr.toString(),
                    DsfinvkCsvUtils.escapeString(row.zahlartTyp),
                    DsfinvkCsvUtils.escapeString(row.zahlartName),
                    DsfinvkCsvUtils.formatDecimal(row.zZahlartBetrag),
                )
            }

        return DsfinvkCsvUtils.buildCsv(HEADER, dataRows)
    }
}
