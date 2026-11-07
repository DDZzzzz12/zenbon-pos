/**
 * CSV exporter for DSFinV-K itemamounts.csv (Bonpos_Preisfindung).
 *
 * Generates the itemamounts.csv file containing price finding/breakdown data
 * according to DSFinV-K specification. Shows how final prices were calculated
 * including base prices, discounts, and surcharges.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Exporter for itemamounts.csv (Bonpos_Preisfindung data).
 *
 * Documents price breakdown for line items:
 * - Base price (Basispreis)
 * - Applied discounts (Rabatt) as negative amounts
 * - Applied surcharges (Aufschlag) as positive amounts
 *
 * Only generated for items with price adjustments.
 */
object ItemAmountsCsvExporter {
    /**
     * CSV header for itemamounts.csv per DSFinV-K specification.
     */
    private val HEADER =
        DsfinvkCsvUtils.joinRow(
            "Z_KASSE_ID",
            "Z_ERSTELLUNG",
            "Z_NR",
            "BON_ID",
            "POS_ZEILE",
            "TYP",
            "UST_SCHLUESSEL",
            "PF_BRUTTO",
            "PF_NETTO",
            "PF_UST",
        )

    /**
     * Generates itemamounts.csv content.
     *
     * @param rows List of price finding data
     * @return CSV content as ByteArray (UTF-8 encoded)
     */
    fun generateCsv(rows: List<DsfinvkExportDto.PriceFindingRowDto>): ByteArray {
        if (rows.isEmpty()) return DsfinvkCsvUtils.buildCsv(HEADER, emptyList())

        val dataRows =
            rows.map { row ->
                DsfinvkCsvUtils.joinRow(
                    DsfinvkCsvUtils.escapeString(row.zKasseId),
                    DsfinvkCsvUtils.escapeString(row.zErstellung),
                    row.zNr.toString(),
                    DsfinvkCsvUtils.escapeString(row.bonId),
                    row.posZeile.toString(),
                    DsfinvkCsvUtils.escapeString(row.typ),
                    row.ustSchluessel.toString(),
                    DsfinvkCsvUtils.formatDecimal(row.pfBrutto),
                    DsfinvkCsvUtils.formatDecimal(row.pfNetto),
                    DsfinvkCsvUtils.formatDecimal(row.pfUst),
                )
            }

        return DsfinvkCsvUtils.buildCsv(HEADER, dataRows)
    }
}
