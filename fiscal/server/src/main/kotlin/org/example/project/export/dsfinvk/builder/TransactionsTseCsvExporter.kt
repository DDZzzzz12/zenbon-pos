/**
 * CSV exporter for DSFinV-K transactions_tse.csv (TSE signature data).
 *
 * Generates the transactions_tse.csv file containing TSE transaction
 * signature data according to DSFinV-K 2.4 specification.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Exporter for transactions_tse.csv (TSE signature data).
 *
 * Note: TSE_VORGANGSDATEN (optional, Anhang E p. 105) carries the signed processData when it can
 * be emitted faithfully — see `DsfinvkExportServiceImpl.tseVorgangsdatenFor`, which sources it from
 * `TseTransaction.processDataHash` (the raw payload, despite the legacy field name) for NORMAL-mode
 * rows, trims surrounding spaces per the spec, and omits CR/LF-bearing Bestellung-V1 payloads that
 * `escapeString` would otherwise mangle. The earlier claim that pos-tse does not surface the
 * payload was false: the QR path already reads it back.
 */
object TransactionsTseCsvExporter {
    /**
     * CSV header for transactions_tse.csv per DSFinV-K 2.4 specification.
     */
    private val HEADER =
        DsfinvkCsvUtils.joinRow(
            "Z_KASSE_ID",
            "Z_ERSTELLUNG",
            "Z_NR",
            "BON_ID",
            "TSE_ID",
            "TSE_TANR",
            "TSE_TA_START",
            "TSE_TA_ENDE",
            "TSE_TA_VORGANGSART",
            "TSE_TA_SIGZ",
            "TSE_TA_SIG",
            "TSE_TA_FEHLER",
            "TSE_VORGANGSDATEN",
        )

    /**
     * Generates transactions_tse.csv content using TseTransaktionenRowDto.
     *
     * @param rows List of TSE signature data
     * @return CSV content as ByteArray
     */
    fun generateCsv(rows: List<DsfinvkExportDto.TseTransaktionenRowDto>): ByteArray {
        if (rows.isEmpty()) return DsfinvkCsvUtils.buildCsv(HEADER, emptyList())

        val dataRows =
            rows.map { row ->
                DsfinvkCsvUtils.joinRow(
                    DsfinvkCsvUtils.escapeString(row.zKasseId),
                    DsfinvkCsvUtils.escapeString(row.zErstellung),
                    row.zNr.toString(),
                    DsfinvkCsvUtils.escapeString(row.bonId),
                    row.tseId?.toString() ?: "",
                    row.tseTanr?.toString() ?: "",
                    DsfinvkCsvUtils.escapeString(row.tseTaStart),
                    DsfinvkCsvUtils.escapeString(row.tseTaEnde),
                    DsfinvkCsvUtils.escapeString(row.tseTaVorgangsart),
                    row.tseTaSigz?.toString() ?: "",
                    DsfinvkCsvUtils.escapeString(row.tseTaSig),
                    DsfinvkCsvUtils.escapeString(row.tseTaFehler),
                    DsfinvkCsvUtils.escapeString(row.tseVorgangsdaten),
                )
            }

        return DsfinvkCsvUtils.buildCsv(HEADER, dataRows)
    }
}
