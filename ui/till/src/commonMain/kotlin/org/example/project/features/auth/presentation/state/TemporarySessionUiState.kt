package org.example.project.features.auth.presentation.state

import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.presentation.error.UiError

/**
 * UI state for temporary manager session management.
 *
 * Tracks the active session status, countdown timer, and UI interactions
 * for privilege escalation sessions.
 *
 * @property activeSession Current active session data (null if no session)
 * @property isDialogVisible Whether the authentication dialog is shown
 * @property isLoading Whether an operation is in progress
 * @property remainingSeconds Seconds until session expiration (0 if no session)
 * @property error UI error to display (null if no error)
 * @property lastSyncTimestamp Timestamp of last server sync
 * @property approvalExpiredNotice One-shot: the elevation just ended on its own (idle timeout, the
 *   server's hard cap, or the server ended it), so the manager's PIN is needed again. A manual end and a
 *   sign-out raise none. The screen shows a notice and consumes it.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
data class TemporarySessionUiState(
    val activeSession: AuthDto.TemporarySessionResponseDto? = null,
    val isDialogVisible: Boolean = false,
    val isLoading: Boolean = false,
    val remainingSeconds: Int = 0,
    val error: UiError? = null,
    val successMessage: String? = null,
    val lastSyncTimestamp: Long = 0L,
    val approvalExpiredNotice: Boolean = false,
    /** With [approvalExpiredNotice]: the hard cap ended it (true), or it simply ran out / the server ended it (false). */
    val approvalExpiredAtCap: Boolean = false,
) {
    /**
     * Whether an active session exists.
     */
    val hasActiveSession: Boolean
        get() = activeSession != null && remainingSeconds > 0

    /**
     * Whether the session is about to expire (≤ 30 seconds remaining).
     */
    val isExpiringSoon: Boolean
        get() = hasActiveSession && remainingSeconds <= 30

    /**
     * Formatted time remaining in M:SS format.
     *
     * Examples: "2:45", "0:30", "0:00"
     */
    val formattedTimeRemaining: String
        get() {
            if (remainingSeconds <= 0) return "0:00"
            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60
            return "$minutes:${seconds.toString().padStart(2, '0')}"
        }

    /**
     * Whether the active session's expiry sits at its hard cap, i.e. it cannot be extended any
     * further and ends for good when the countdown runs out. False against a server that predates
     * the cap (no `hardCapAt`).
     */
    val isAtHardCap: Boolean
        get() = activeSession?.let { it.hardCapAt != null && it.expiresAt == it.hardCapAt } ?: false

    /**
     * Manager name from active session, or null if no session.
     */
    val managerName: String?
        get() = activeSession?.managerName
}
