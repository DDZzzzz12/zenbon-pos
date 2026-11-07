package org.example.project.features.auth.data.remote

import org.example.project.core.domain.dto.auth.AuthDto

/**
 * HTTP client for temporary manager session API calls.
 *
 * Provides methods for creating, querying, extending, and terminating
 * temporary manager sessions that grant elevated permissions for a limited time.
 *
 * All sessions are device-specific and require manager authentication.
 *
 * Stage C migration: throws [org.example.project.core.network.error.PosApiException] /
 * [org.example.project.core.network.error.NetworkApiException] on failure.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
interface TemporarySessionApiClient {
    /**
     * Create a new temporary manager session.
     *
     * Authenticates manager credentials (username + PIN) and creates a
     * 3-minute session on the current device. Only one session can be
     * active per device at a time.
     *
     * @param request Session creation request with manager credentials
     * @return Created session info; throws on failure.
     */
    suspend fun createSession(request: AuthDto.TemporarySessionRequestDto): AuthDto.TemporarySessionResponseDto

    /**
     * Get the active temporary session for the current device.
     *
     * Uses device ID from JWT token for secure device identification.
     * Returns null if no active session exists (HTTP 204 No Content).
     *
     * @return Session info or null when no active session.
     */
    suspend fun getActiveSession(): AuthDto.TemporarySessionResponseDto?

    /**
     * Extend an existing temporary session by 3 minutes.
     *
     * Resets the expiration timer for the specified session.
     * Fails if the session has already expired.
     *
     * @param sessionId Unique session identifier
     * @return Updated session info; throws on failure.
     */
    suspend fun extendSession(sessionId: String): AuthDto.TemporarySessionResponseDto

    /**
     * Manually end a temporary session before expiration.
     *
     * Immediately terminates the specified session and removes
     * associated elevated permissions.
     *
     * @param sessionId Unique session identifier
     */
    suspend fun endSession(sessionId: String)
}
