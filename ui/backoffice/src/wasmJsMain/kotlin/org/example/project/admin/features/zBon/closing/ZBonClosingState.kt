package org.example.project.admin.features.zBon.closing

import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.presentation.error.UiError
import org.example.project.features.reports.presentation.state.ZBonClosingDraft

/** The adminApp's closing: the open dialog's draft, the closing just created, and the last error. */
data class ZBonClosingState(
    val draft: ZBonClosingDraft? = null,
    val isSubmitting: Boolean = false,
    val created: ZBonDto.ZBonResponseDto? = null,
    /**
     * The shop's currency as reported by the readiness the closing was confirmed under; kept with
     * [created] so the summary uses the same currency once the draft is gone. Null while unknown.
     */
    val createdCurrency: String? = null,
    val error: UiError? = null,
)
