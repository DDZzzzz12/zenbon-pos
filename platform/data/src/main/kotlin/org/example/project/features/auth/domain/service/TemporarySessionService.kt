/**
 * Service interface for temporary manager session management.
 *
 * Defines operations for creating, validating, and managing device-specific
 * temporary sessions where managers can authorize employees for restricted operations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.auth.domain.service

import org.example.project.core.domain.dto.auth.AuthDto

/**
 * Temporary session service interface.
 *
 * Manages device-specific manager sessions with 5-minute duration.
 * Sessions allow employees on a specific device to perform restricted
 * operations without individual approvals.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts to application/problem+json.
 */
interface TemporarySessionService {
    /**
     * Create a new temporary manager session on a device.
     *
     * Validates manager credentials (username + PIN) and creates a 5-minute
     * session tied to the specific device. Only one session can be active
     * per device at a time.
     *
     * @param managerUsername Manager's username
     * @param managerPin Manager's PIN for authentication
     * @param deviceId Unique device identifier
     * @param requestingEmployeeId ID of the employee requesting the session
     * @return Session information
     */
    suspend fun createSession(
        managerUsername: String,
        managerPin: String,
        deviceId: String,
        requestingEmployeeId: String,
        /**
         * The capability the blocked action needed, or null for the generic "manager override"
         * dialog, which is opened from the drawer and does not know what prompted it.
         *
         * Null lends `ElevationScope.TILL_OVERRIDES` — small, listed, and reviewable — rather than
         * the approver's whole reach. Naming one narrows the lend to exactly that.
         */
        requestedCapability: String?,
        /**
         * True when the caller is an ORDER STATION (its JWT carries the `station` claim). The lend
         * is then scoped to `ElevationScope.STATION_OVERRIDES` (item Storno + transfer) and the
         * session is marked `onStation`, so its override actions are attributed to the manager
         * (owner decision 2026-10-03, emu21 NOTE-v).
         */
        onStation: Boolean = false,
    ): AuthDto.TemporarySessionResponseDto

    /**
     * Validate that an active session exists for a device.
     *
     * Checks if the session exists, is not expired, and belongs to the device.
     *
     * @param sessionId Session identifier
     * @param deviceId Device identifier
     * @return Manager ID associated with the session
     */
    suspend fun validateSession(
        sessionId: String,
        deviceId: String,
    ): String

    /**
     * Extend a live session: its expiry becomes now + the session length a fresh step-up gets, never
     * more, and never past `createdAt + TemporarySessionStore.MAX_LIFETIME` (the hard cap). An
     * expired or unknown session cannot be revived — the manager's PIN is needed again; one that ran
     * into the hard cap is refused with `AUTH_STEP_UP_CAP_REACHED` (409).
     *
     * @param sessionId Session identifier
     * @param requestingEmployeeId The caller; only the employee the session was lent to may extend it
     * @return Updated session information
     */
    suspend fun extendSession(
        sessionId: String,
        requestingEmployeeId: String,
    ): AuthDto.TemporarySessionResponseDto

    /**
     * End a session before expiry, withdrawing the lent capability immediately.
     *
     * Idempotent: a session that already expired or was ended is simply gone.
     *
     * @param sessionId Session identifier
     * @param requestingEmployeeId The caller; only the employee the session was lent to may end it
     */
    suspend fun endSession(
        sessionId: String,
        requestingEmployeeId: String,
    )

    /**
     * Get active session for a device if one exists.
     *
     * @param deviceId Device identifier
     * @return Session information, or null if none active
     */
    suspend fun getActiveSession(deviceId: String): AuthDto.TemporarySessionResponseDto?
}
