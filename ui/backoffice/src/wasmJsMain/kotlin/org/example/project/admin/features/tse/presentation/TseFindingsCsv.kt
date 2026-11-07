package org.example.project.admin.features.tse.presentation

import org.example.project.core.domain.dto.tse.TseExplanationDto

/**
 * The explanation register as CSV, for the Verfahrensdokumentation or an auditor (design D7).
 *
 * Semicolon-separated and CRLF-terminated, the same shape the DATEV and DSFinV-K exports use, so a
 * German Excel opens it without an import dialog. Only the free text is quoted — it is the one
 * column that can contain a separator or a quote of its own.
 */
object TseFindingsCsv {
    private const val HEADER =
        "id;status;superseded_by;tse_serial_hex;tanr_from;tanr_to;category;source;created_at;" +
            "created_by;created_by_number;supersedes_id;text"

    /**
     * One row per entry, superseded ones included. `status` and `superseded_by` are derived from
     * the `supersedes_id` links, and the `id` column is what those links point at — without it a
     * reader could not tell which entry a correction replaced (E2E round 4, R4-5).
     */
    fun build(explanations: List<TseExplanationDto.ExplanationDto>): String {
        val supersededBy = explanations.mapNotNull { e -> e.supersedesId?.let { it to e.id } }.toMap()
        return (listOf(HEADER) + explanations.sortedBy { it.id }.map { row(it, supersededBy[it.id]) }).joinToString("\r\n")
    }

    private fun row(
        explanation: TseExplanationDto.ExplanationDto,
        supersededBy: Long?,
    ): String =
        listOf(
            explanation.id.toString(),
            if (supersededBy == null) CURRENT else SUPERSEDED,
            supersededBy?.toString().orEmpty(),
            explanation.tseSerialHex,
            explanation.tanrFrom.toString(),
            explanation.tanrTo.toString(),
            explanation.category.name,
            explanation.source.name,
            explanation.createdAt,
            explanation.createdByEmployeeId.orEmpty(),
            explanation.createdByEmployeeNumber.orEmpty(),
            explanation.supersedesId?.toString().orEmpty(),
            quote(explanation.text),
        ).joinToString(";")

    private fun quote(text: String): String = "\"" + text.replace("\"", "\"\"") + "\""

    /** Machine values, like the category column — the file is read by tools as well as people. */
    private const val CURRENT = "CURRENT"
    private const val SUPERSEDED = "SUPERSEDED"
}
