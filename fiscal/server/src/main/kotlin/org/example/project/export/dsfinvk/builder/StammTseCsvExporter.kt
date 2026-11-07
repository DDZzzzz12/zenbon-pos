/**
 * CSV exporter for DSFinV-K tse.csv (Stamm_TSE / TSE master data).
 *
 * Generates the tse.csv file containing TSE device information
 * according to DSFinV-K specification 3.2.7.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.export.dsfinvk.model.StammTseCertificate

/**
 * Exporter for tse.csv (Stamm_TSE / TSE master data).
 */
object StammTseCsvExporter {
    /** The fixed Stamm_TSE columns preceding the variable TSE_ZERTIFIKAT_* fields. */
    private val FIXED_HEADER =
        listOf(
            "Z_KASSE_ID",
            "Z_ERSTELLUNG",
            "Z_NR",
            "TSE_ID",
            "TSE_SERIAL",
            "TSE_SIG_ALGO",
            "TSE_ZEITFORMAT",
            "TSE_PD_ENCODING",
            "TSE_PUBLIC_KEY",
        )

    /**
     * Generates tse.csv content.
     *
     * The number of TSE_ZERTIFIKAT_* columns is data-driven and shared with index.xml through
     * [StammTseCertificate.fieldCount], so tse.csv and the descriptor never disagree (audit R2).
     * A certificate longer than 2000 chars spreads across TSE_ZERTIFIKAT_III/IV/V… (Anhang E
     * p. 78-79); shorter-certificate devices leave the higher fields empty.
     *
     * @param rows List of TSE master data
     * @return CSV content as ByteArray (UTF-8 encoded)
     */
    fun generateCsv(rows: List<DsfinvkExportDto.StammTseRowDto>): ByteArray {
        val fieldCount = StammTseCertificate.fieldCount(rows)
        val certColumns = (1..fieldCount).map { StammTseCertificate.fieldName(it) }
        val header = DsfinvkCsvUtils.joinRow(*(FIXED_HEADER + certColumns).toTypedArray())

        if (rows.isEmpty()) return DsfinvkCsvUtils.buildCsv(header, emptyList())

        val dataRows =
            rows.map { row ->
                val cells =
                    buildList {
                        add(DsfinvkCsvUtils.escapeString(row.zKasseId))
                        add(DsfinvkCsvUtils.escapeString(row.zErstellung))
                        add(row.zNr.toString())
                        add(row.tseId.toString())
                        add(DsfinvkCsvUtils.escapeString(row.tseSerial))
                        add(DsfinvkCsvUtils.escapeString(row.tseSigAlgo))
                        add(DsfinvkCsvUtils.escapeString(row.tseZeitformat))
                        add(DsfinvkCsvUtils.escapeString(row.tsePdEncoding))
                        add(DsfinvkCsvUtils.escapeString(row.tsePublicKey))
                        for (i in 0 until fieldCount) {
                            add(DsfinvkCsvUtils.escapeString(row.tseZertifikatParts.getOrNull(i) ?: ""))
                        }
                    }
                DsfinvkCsvUtils.joinRow(*cells.toTypedArray())
            }

        return DsfinvkCsvUtils.buildCsv(header, dataRows)
    }
}
