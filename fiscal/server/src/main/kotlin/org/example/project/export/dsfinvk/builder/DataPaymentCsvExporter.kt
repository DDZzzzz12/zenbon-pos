/**
 * CSV exporter for DSFinV-K datapayment.csv (Bonkopf_Zahlarten).
 *
 * Generates the datapayment.csv file containing individual payment
 * transaction data according to DSFinV-K 2.4 specification.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Exporter for datapayment.csv (Bonkopf_Zahlarten).
 */
object DataPaymentCsvExporter {
    /**
     * CSV header for datapayment.csv per DSFinV-K 2.4 specification.
     */
    private val HEADER =
        DsfinvkCsvUtils.joinRow(
            "Z_KASSE_ID",
            "Z_ERSTELLUNG",
            "Z_NR",
            "BON_ID",
            "ZAHLART_TYP",
            "ZAHLART_NAME",
            "ZAHLWAEH_CODE",
            "ZAHLWAEH_BETRAG",
            "BASISWAEH_BETRAG",
        )

    /**
     * Generates datapayment.csv content using BonkopfZahlartenRowDto.
     *
     * @param rows List of individual payment data
     * @return CSV content as ByteArray
     */
    fun generateCsv(rows: List<DsfinvkExportDto.BonkopfZahlartenRowDto>): ByteArray {
        if (rows.isEmpty()) return DsfinvkCsvUtils.buildCsv(HEADER, emptyList())

        val dataRows =
            rows.map { row ->
                DsfinvkCsvUtils.joinRow(
                    DsfinvkCsvUtils.escapeString(row.zKasseId),
                    DsfinvkCsvUtils.escapeString(row.zErstellung),
                    row.zNr.toString(),
                    DsfinvkCsvUtils.escapeString(row.bonId),
                    DsfinvkCsvUtils.escapeString(row.zahlartTyp),
                    DsfinvkCsvUtils.escapeString(row.zahlartName),
                    DsfinvkCsvUtils.escapeString(row.zahlwaehCode),
                    DsfinvkCsvUtils.formatDecimal(row.zahlwaehBetrag),
                    DsfinvkCsvUtils.formatDecimal(row.basiswaehBetrag),
                )
            }

        return DsfinvkCsvUtils.buildCsv(HEADER, dataRows)
    }
}
