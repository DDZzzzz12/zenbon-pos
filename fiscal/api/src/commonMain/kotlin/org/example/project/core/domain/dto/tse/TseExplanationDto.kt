package org.example.project.core.domain.dto.tse

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.tse.TseExplanationCategory
import org.example.project.core.domain.enums.tse.TseExplanationSource
import org.example.project.core.domain.enums.tse.TseFindingKind

/** TSE findings and their explanations (design 2026-09-22 §4.7). */
object TseExplanationDto {
    @Serializable
    data class ExplanationDto(
        val id: Long,
        val tseSerialHex: String,
        val tanrFrom: Long,
        val tanrTo: Long,
        val category: TseExplanationCategory,
        val text: String,
        val source: TseExplanationSource,
        val createdAt: String,
        val createdByEmployeeId: String? = null,
        /** The author's employee number, resolved by the server; null for SYSTEM entries. */
        val createdByEmployeeNumber: String? = null,
        val supersedesId: Long? = null,
    )

    @Serializable
    data class CreateRequestDto(
        val tseSerialHex: String,
        val tanrFrom: Long,
        val tanrTo: Long,
        val category: TseExplanationCategory,
        val text: String,
        val supersedesId: Long? = null,
    ) {
        /** Null when valid; otherwise the reason. The server and the dialog check the same rules (rule 8). */
        fun validationError(): String? =
            when {
                tseSerialHex.isBlank() -> "tseSerialHex is required"
                tanrFrom > tanrTo -> "tanrFrom must not exceed tanrTo"
                text.isBlank() -> "text is required"
                text.length > MAX_TEXT -> "text exceeds $MAX_TEXT characters"
                else -> null
            }

        companion object {
            const val MAX_TEXT = 2000
        }
    }

    @Serializable
    data class FindingDto(
        val tseSerialHex: String,
        val tanrFrom: Long,
        val tanrTo: Long,
        val kind: TseFindingKind,
        /** The current explanation covering this finding, or null when it is unexplained. */
        val explanationId: Long? = null,
    )

    @Serializable
    data class FindingsResponseDto(
        val findings: List<FindingDto>,
        /** Current explanations only: an entry corrected by a later one is left out. */
        val explanations: List<ExplanationDto>,
        /**
         * The whole register in write order, superseded entries included — what the register's
         * export carries (E2E round 4, R4-5). Defaulted so an older server's reply still decodes.
         */
        val history: List<ExplanationDto> = emptyList(),
    )
}
