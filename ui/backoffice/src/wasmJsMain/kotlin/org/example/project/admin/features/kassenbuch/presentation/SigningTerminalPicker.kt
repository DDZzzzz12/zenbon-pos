package org.example.project.admin.features.kassenbuch.presentation

import org.example.project.core.domain.dto.kassenbuch.CashBookDto

/** The fiscal book's signing-till picker. Hidden without `TERMINAL_MANAGE` or on a non-fiscal book. */
data class SigningTerminalPicker(
    val visible: Boolean = false,
    val candidates: List<CashBookDto.SigningTerminalCandidateDto> = emptyList(),
    val selectedId: Long? = null,
    /** The designation the server holds, so "unchanged" can be told from "edited". */
    val savedId: Long? = null,
    /** [selectedId] was proposed (the only eligible till), not yet saved. */
    val isProposal: Boolean = false,
    val isSaving: Boolean = false,
) {
    val selectedName: String?
        get() = candidates.firstOrNull { it.terminalId == selectedId }?.name

    val hasUnsavedChange: Boolean
        get() = visible && (isProposal || selectedId != savedId)
}
