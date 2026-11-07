package org.example.project.admin.features.dashboard.presentation.sections

import org.example.project.core.domain.dto.auth.AuthDto

/**
 * [denyingRequestId] drives the reason dialog: an id means the dialog is open for that request, and
 * it is cleared on submit or dismiss so a reason typed against one request can never be sent
 * against the next.
 */
data class PendingAuthorizationsState(
    val isLoading: Boolean = true,
    val failed: Boolean = false,
    val requests: List<AuthDto.ApprovalRequestDto> = emptyList(),
    val denyingRequestId: String? = null,
) {
    val isEmpty: Boolean get() = requests.isEmpty()
}
