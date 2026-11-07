package org.example.project.admin.features.staff.presentation.approvals

import org.example.project.core.domain.dto.timetracking.TimeApprovalDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey

sealed interface ApprovalsState {
    data object Loading : ApprovalsState

    data class Error(
        val error: UiError,
    ) : ApprovalsState

    data class Data(
        val pending: List<TimeApprovalDto.PendingApprovalDto>,
    ) : ApprovalsState
}

/** Draft for the reject-with-reason dialog. */
data class RejectDraft(
    val approvalId: String,
    val reason: String = "",
    val saving: Boolean = false,
    /** A server refusal — belongs to no field. A blank reason is a [fieldErrors] entry instead. */
    val error: UiError? = null,
    /** Per-field validation refusals from the Reject click, shown at the field. */
    val fieldErrors: Map<RejectField, StringKey> = emptyMap(),
    /** Bumped on every Reject click the validation refuses; the dialog focuses the first error on each bump. */
    val validationAttempt: Int = 0,
)
