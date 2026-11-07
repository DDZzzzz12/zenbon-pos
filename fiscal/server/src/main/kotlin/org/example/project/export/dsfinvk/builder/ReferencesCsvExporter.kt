/**
 * CSV exporter for DSFinV-K references.csv (Bon_Referenzen).
 *
 * Generates the references.csv file containing transaction reference data
 * according to DSFinV-K specification. Tracks relationships between transactions,
 * particularly for storno/cancellation references and split bills.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Exporter for references.csv (Bon_Referenzen data).
 *
 * Tracks references between transactions for:
 * - Storno/cancellation references to original receipts
 * - Item-level cancellation references
 * - Split bill references
 * - External document references
 */
object ReferencesCsvExporter {
    /**
     * CSV header for references.csv per DSFinV-K specification.
     */
    private val HEADER =
        DsfinvkCsvUtils.joinRow(
            "Z_KASSE_ID",
            "Z_ERSTELLUNG",
            "Z_NR",
            "BON_ID",
            "POS_ZEILE",
            "REF_TYP",
            "REF_NAME",
            "REF_DATUM",
            "REF_Z_KASSE_ID",
            "REF_Z_NR",
            "REF_BON_ID",
        )

    /**
     * Generates references.csv content.
     *
     * @param rows List of reference data
     * @return CSV content as ByteArray (UTF-8 encoded)
     */
    fun generateCsv(rows: List<DsfinvkExportDto.ReferenceRowDto>): ByteArray {
        if (rows.isEmpty()) return DsfinvkCsvUtils.buildCsv(HEADER, emptyList())

        val dataRows =
            rows.map { row ->
                DsfinvkCsvUtils.joinRow(
                    DsfinvkCsvUtils.escapeString(row.zKasseId),
                    DsfinvkCsvUtils.escapeString(row.zErstellung),
                    row.zNr.toString(),
                    DsfinvkCsvUtils.escapeString(row.bonId),
                    DsfinvkCsvUtils.escapeString(row.posZeile),
                    DsfinvkCsvUtils.escapeString(row.refTyp),
                    DsfinvkCsvUtils.escapeString(row.refName),
                    DsfinvkCsvUtils.escapeString(row.refDatum),
                    DsfinvkCsvUtils.escapeString(row.refZKasseId),
                    DsfinvkCsvUtils.formatLong(row.refZNr),
                    DsfinvkCsvUtils.escapeString(row.refBonId),
                )
            }

        return DsfinvkCsvUtils.buildCsv(HEADER, dataRows)
    }
}
