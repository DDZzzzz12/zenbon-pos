/**
 * CSV exporter for DSFinV-K subitems.csv (Bonpos_Zusatzinfo).
 *
 * Generates the subitems.csv file containing sub-item/extras data
 * according to DSFinV-K specification. Documents product modifiers,
 * add-ons, and item customizations with price impact.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Exporter for subitems.csv (Bonpos_Zusatzinfo data).
 *
 * Documents sub-items for line items:
 * - Product extras/modifiers (toppings, add-ons)
 * - Combo/bundle components
 * - Item customizations with price adjustments
 */
object SubItemsCsvExporter {
    /**
     * CSV header for subitems.csv per DSFinV-K specification.
     */
    private val HEADER =
        DsfinvkCsvUtils.joinRow(
            "Z_KASSE_ID",
            "Z_ERSTELLUNG",
            "Z_NR",
            "BON_ID",
            "POS_ZEILE",
            "ZI_ART_NR",
            "ZI_GTIN",
            "ZI_NAME",
            "ZI_WARENGR_ID",
            "ZI_WARENGR",
            "ZI_MENGE",
            "ZI_FAKTOR",
            "ZI_EINHEIT",
            "ZI_UST_SCHLUESSEL",
            "ZI_BASISPREIS_BRUTTO",
            "ZI_BASISPREIS_NETTO",
            "ZI_BASISPREIS_UST",
        )

    /**
     * Generates subitems.csv content.
     *
     * @param rows List of sub-item data
     * @return CSV content as ByteArray (UTF-8 encoded)
     */
    fun generateCsv(rows: List<DsfinvkExportDto.SubItemRowDto>): ByteArray {
        if (rows.isEmpty()) return DsfinvkCsvUtils.buildCsv(HEADER, emptyList())

        val dataRows =
            rows.map { row ->
                DsfinvkCsvUtils.joinRow(
                    DsfinvkCsvUtils.escapeString(row.zKasseId),
                    DsfinvkCsvUtils.escapeString(row.zErstellung),
                    row.zNr.toString(),
                    DsfinvkCsvUtils.escapeString(row.bonId),
                    // POS_ZEILE is Alpha(50) in index.xml — encapsulate like every other Alpha column
                    // (m19), even though this DTO field happens to be numeric.
                    DsfinvkCsvUtils.escapeString(row.posZeile.toString()),
                    DsfinvkCsvUtils.escapeString(row.ziArtNr),
                    DsfinvkCsvUtils.escapeString(row.ziGtin),
                    DsfinvkCsvUtils.escapeString(row.ziName),
                    DsfinvkCsvUtils.escapeString(row.ziWarengrId),
                    DsfinvkCsvUtils.escapeString(row.ziWarengr),
                    DsfinvkCsvUtils.formatDecimal(row.ziMenge),
                    DsfinvkCsvUtils.formatDecimal(row.ziFaktor),
                    DsfinvkCsvUtils.escapeString(row.ziEinheit),
                    row.ziUstSchluessel.toString(),
                    DsfinvkCsvUtils.formatDecimal(row.ziBasispreisBrutto),
                    DsfinvkCsvUtils.formatDecimal(row.ziBasispreisNetto),
                    DsfinvkCsvUtils.formatDecimal(row.ziBasispreisUst),
                )
            }

        return DsfinvkCsvUtils.buildCsv(HEADER, dataRows)
    }
}
