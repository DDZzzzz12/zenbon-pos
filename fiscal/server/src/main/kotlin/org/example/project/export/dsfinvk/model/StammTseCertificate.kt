/**
 * Single source of truth for how a TSE certificate is spread across the Stamm_TSE
 * `TSE_ZERTIFIKAT_*` fields — shared by BOTH producers of that fact so tse.csv and
 * index.xml can never disagree (audit R2):
 *
 *  - [StammTseCsvExporter] emits the actual `TSE_ZERTIFIKAT_N` header + cell values.
 *  - [DsfinvkIndexXml]/[DsfinvkIndexXmlGenerator] declare the matching `VariableColumn`
 *    descriptors auditor software parses tse.csv through.
 *
 * DSFinV-K Anhang E p. 78-79 (TSE_ZERTIFIKAT_I / TSE_ZERTIFIKAT_II):
 *   „Das Zertifikat der TSE in Base64-Codierung (falls mehr als 1.000 Zeichen nur die
 *    ersten 1.000)." / „Rest des Zertifikats in Base64-Codierung"
 *   „Hinweis: Werden weitere Zertifikatsfelder benötigt (Zertifikat > 2.000 Zeichen),
 *    können diese als Felder TSE_ZERTIFIKAT_III, TSE_ZERTIFIKAT_IV, TSE_ZERTIFIKAT_V usw.
 *    angelegt werden. Eine Ergänzung in der Beschreibungs-Datei index.xml ist dann
 *    zusätzlich vorzunehmen."
 *
 * So the certificate is split into 1000-char base64 chunks across as many
 * `TSE_ZERTIFIKAT_N` fields (I, II, III, IV, V …) as it needs — never truncated. A real
 * production certificate of 4316 chars fills five fields (I..V).
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.model

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Helper computing the `TSE_ZERTIFIKAT_*` field layout for Stamm_TSE.
 */
object StammTseCertificate {
    /** Per Anhang E each certificate field carries at most 1000 base64 chars. */
    const val CHUNK_SIZE = 1000

    /**
     * The baseline Stamm_TSE schema always declares TSE_ZERTIFIKAT_I and _II, even when the
     * certificate is short or absent (the reference index.xml declares both). Additional fields
     * appear only when a certificate needs more than two.
     */
    const val MIN_FIELDS = 2

    /**
     * Splits a certificate into ≤1000-char base64 chunks in order (part 0 → TSE_ZERTIFIKAT_I).
     * A null/blank certificate yields no parts. Never truncates — the whole certificate is
     * preserved across however many chunks it takes.
     */
    fun chunk(certificate: String?): List<String> {
        if (certificate.isNullOrEmpty()) return emptyList()
        return certificate.chunked(CHUNK_SIZE)
    }

    /**
     * The number of `TSE_ZERTIFIKAT_*` columns tse.csv (one CSV, one shared column set) must
     * carry for a given set of Stamm_TSE rows: `max(MIN_FIELDS, longest certificate's part count)`.
     * Data-driven and per-file, so re-exports are byte-stable and shorter-certificate devices
     * simply leave the higher fields empty.
     */
    fun fieldCount(rows: List<DsfinvkExportDto.StammTseRowDto>): Int = maxOf(MIN_FIELDS, rows.maxOfOrNull { it.tseZertifikatParts.size } ?: 0)

    /**
     * The DSFinV-K column name for the 1-based certificate field index: 1 → `TSE_ZERTIFIKAT_I`,
     * 2 → `TSE_ZERTIFIKAT_II`, 3 → `TSE_ZERTIFIKAT_III`, … (Roman numerals per Anhang E).
     */
    fun fieldName(oneBasedIndex: Int): String = "TSE_ZERTIFIKAT_${toRoman(oneBasedIndex)}"

    private val ROMAN_NUMERALS =
        listOf(
            1000 to "M",
            900 to "CM",
            500 to "D",
            400 to "CD",
            100 to "C",
            90 to "XC",
            50 to "L",
            40 to "XL",
            10 to "X",
            9 to "IX",
            5 to "V",
            4 to "IV",
            1 to "I",
        )

    private fun toRoman(value: Int): String {
        require(value in 1..3999) { "TSE_ZERTIFIKAT field index out of range: $value" }
        var remainder = value
        val builder = StringBuilder()
        for ((magnitude, symbol) in ROMAN_NUMERALS) {
            while (remainder >= magnitude) {
                builder.append(symbol)
                remainder -= magnitude
            }
        }
        return builder.toString()
    }
}
