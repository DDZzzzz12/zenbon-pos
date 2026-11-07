package org.example.project.features.auth.presentation.state

import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.presentation.error.UiError

/**
 * UI state for the manager-side approval request workflow.
 */
data class ApprovalRequestUiState(
    val pendingRequests: List<AuthDto.ApprovalRequestDto> = emptyList(),
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val currentDialog: ApprovalDialogState? = null,
)

/**
 * State for the approval dialog.
 */
sealed class ApprovalDialogState {
    data class ShowRequest(
        val request: AuthDto.ApprovalRequestDto,
    ) : ApprovalDialogState()

    data class DenyConfirmation(
        val request: AuthDto.ApprovalRequestDto,
    ) : ApprovalDialogState()
}
