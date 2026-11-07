package org.example.project.admin.features.tse.presentation

import org.example.project.core.domain.dto.tse.TseExplanationDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey

/**
 * What the TSE-Befunde screen shows.
 *
 * [explainError] is a field-level error for the explain dialog and is deliberately separate from
 * [error]: a blank explanation belongs under the input that caused it, not in a global toast.
 *
 * [explanations] carries only the **current** entry per range — the server leaves out an entry a
 * later one corrects — so looking a finding's `explanationId` up here cannot resurface a superseded
 * text.
 */
data class TseFindingsState(
    val findings: List<TseExplanationDto.FindingDto> = emptyList(),
    val explanations: List<TseExplanationDto.ExplanationDto> = emptyList(),
    /** The whole register, superseded entries included — what the CSV export carries. */
    val history: List<TseExplanationDto.ExplanationDto> = emptyList(),
    val isLoading: Boolean = false,
    val isBusy: Boolean = false,
    val explainError: StringKey? = null,
    /**
     * Counts explanations the server accepted. The screen closes the dialog when it changes — a
     * signal that cannot be missed, unlike an `isBusy` true→false flip, which a fast reply can
     * complete inside one frame (E2E round 4, R4-7: the dialog stayed open after a successful save).
     */
    val explainAcceptedCount: Int = 0,
    val error: UiError? = null,
) {
    /** The explanation covering [finding], or null while it is unexplained. */
    fun explanationFor(finding: TseExplanationDto.FindingDto): TseExplanationDto.ExplanationDto? {
        val id = finding.explanationId ?: return null
        return explanations.firstOrNull { it.id == id }
    }
}
