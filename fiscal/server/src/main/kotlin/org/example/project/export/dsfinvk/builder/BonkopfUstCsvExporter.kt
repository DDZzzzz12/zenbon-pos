/**
 * CSV exporter for DSFinV-K transactions_vat.csv (Bonkopf_USt / VAT per receipt header).
 *
 * Generates the transactions_vat.csv file containing VAT breakdown per receipt
 * according to DSFinV-K specification 3.1.2.1.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Exporter for transactions_vat.csv (Bonkopf_USt / VAT per receipt header).
 */
object BonkopfUstCsvExporter {
    /**
     * CSV header for transactions_vat.csv per DSFinV-K specification.
     */
    private val HEADER =
        DsfinvkCsvUtils.joinRow(
            "Z_KASSE_ID",
            "Z_ERSTELLUNG",
            "Z_NR",
            "BON_ID",
            "UST_SCHLUESSEL",
            "BON_BRUTTO",
            "BON_NETTO",
            "BON_UST",
        )

    /**
     * Generates bonkopf_ust.csv content.
     *
     * @param rows List of VAT per receipt data
     * @return CSV content as ByteArray (UTF-8 encoded)
     */
    fun generateCsv(rows: List<DsfinvkExportDto.BonkopfUstRowDto>): ByteArray {
        if (rows.isEmpty()) return DsfinvkCsvUtils.buildCsv(HEADER, emptyList())

        val dataRows =
            rows.map { row ->
                DsfinvkCsvUtils.joinRow(
                    DsfinvkCsvUtils.escapeString(row.zKasseId),
                    DsfinvkCsvUtils.escapeString(row.zErstellung),
                    row.zNr.toString(),
                    DsfinvkCsvUtils.escapeString(row.bonId),
                    row.ustSchluessel.toString(),
                    DsfinvkCsvUtils.formatDecimal(row.bonBrutto),
                    DsfinvkCsvUtils.formatDecimal(row.bonNetto),
                    DsfinvkCsvUtils.formatDecimal(row.bonUst),
                )
            }

        return DsfinvkCsvUtils.buildCsv(HEADER, dataRows)
    }
}
