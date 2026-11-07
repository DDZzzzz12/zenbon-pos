/**
 * In-memory storage for temporary manager sessions and approval requests.
 *
 * Provides thread-safe storage and management of active sessions and pending
 * approval requests with automatic cleanup of expired items.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
@file:OptIn(ExperimentalTime::class)

package org.example.project.features.auth.data

import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Thread-safe in-memory store for authorization data.
 *
 * Manages temporary sessions and approval requests with automatic
 * expiration and cleanup. Data is intentionally volatile (lost on
 * server restart) for security.
 */
object TemporarySessionStore {
    private val logger = LoggerFactory.getLogger(TemporarySessionStore::class.java)

    /** How long a fresh step-up lasts, and how far one extension moves its expiry from "now". */
    val SESSION_LENGTH: Duration = 3.minutes

    /**
     * The hard cap: a step-up never outlives `createdAt + MAX_LIFETIME`, however often it is
     * extended. **The one definition** — the service, the gate and the till (via
     * `TemporarySessionResponseDto.hardCapAt`) all read it from here.
     *
     * Without it, a till that extends on every navigation kept a manager's approval alive for a whole
     * shift after a single PIN entry. At the cap the manager must enter the PIN again.
     */
    val MAX_LIFETIME: Duration = 15.minutes

    /** How long a session that ran into its cap is remembered, so an extend can say why it failed. */
    private val CAPPED_RETENTION: Duration = 1.hours

    /**
     * Active temporary sessions by session ID.
     */
    private val sessions = ConcurrentHashMap<String, SessionInfo>()

    /**
     * Active sessions by device ID for quick lookup.
     */
    private val sessionsByDevice = ConcurrentHashMap<String, String>()

    private val sessionsByEmployee = ConcurrentHashMap<String, String>()

    /**
     * Pending approval requests by request ID.
     */
    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()

    /**
     * Sessions that ended because they reached [MAX_LIFETIME], by session ID.
     *
     * Kept (briefly) only so an extend arriving after the cap is refused as "the approval ran out"
     * rather than the indistinguishable "no such session" — the till must tell the cashier the
     * manager's PIN is needed again.
     */
    private val cappedSessions = ConcurrentHashMap<String, SessionInfo>()

    /**
     * Information about an active temporary session.
     *
     * @property sessionId Unique session identifier
     * @property managerId Manager's employee ID
     * @property managerName Manager's display name
     * @property managerRole Manager's role (MANAGER or ADMIN)
     * @property deviceId Device where session is active
     * @property requestingEmployeeId ID of the employee who requested the session
     * @property grantedCapabilities What the session lends them, on top of what they already hold
     * @property createdAt When session was created
     * @property expiresAt When session expires
     */
    data class SessionInfo(
        val sessionId: String,
        val managerId: String,
        val managerName: String,
        val managerRole: EmployeeRole,
        val deviceId: String,
        val requestingEmployeeId: String,
        /**
         * What this step-up LENDS, on top of what the requesting employee already holds.
         *
         * The session used to replace the requester's identity with the manager's outright. It now
         * unions: the cashier keeps their own capabilities, acts under their own name, and gains
         * exactly this set until the session expires. See `ElevationScope` for why.
         */
        val grantedCapabilities: Set<Capability>,
        val createdAt: Instant,
        val expiresAt: Instant,
        /**
         * True when the session was opened by a manager signing in on an ORDER STATION (owner
         * decision 2026-10-03, emu21 NOTE-v). There nobody but the shared station account is
         * signed in, so the override actions taken under this session are attributed to the
         * MANAGER, not to the station account — see `TokenClaims.overrideActorId()` and
         * [stationOverrideActedBy].
         */
        val onStation: Boolean = false,
    ) {
        /** The latest instant this session may ever live to: `createdAt + MAX_LIFETIME`. */
        fun hardCapAt(): Instant = createdAt.plus(MAX_LIFETIME)

        /** Expired at its own expiry, or at the hard cap, whichever comes first. */
        fun isExpired(): Boolean = Instant.fromEpochMilliseconds(System.currentTimeMillis()) > minOf(expiresAt, hardCapAt())

        /** True once the session's life is bounded by the hard cap rather than by inactivity. */
        fun endsAtHardCap(): Boolean = expiresAt >= hardCapAt()

        fun remainingSeconds(): Int {
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            val remaining = (expiresAt.toEpochMilliseconds() - now.toEpochMilliseconds()) / 1000
            return remaining.toInt().coerceAtLeast(0)
        }
    }

    /**
     * Information about a pending approval request.
     *
     * @property requestId Unique request identifier
     * @property employeeId Requesting employee's ID
     * @property employeeName Requesting employee's name
     * @property deviceId Device where request originated
     * @property operation Operation being requested
     * @property capabilities Capabilities the operation needs
     * @property details Operation-specific details
     * @property createdAt When request was created
     * @property status Current request status
     * @property approvedBy Manager who approved (if any)
     * @property approvedByName Manager name who approved (if any)
     * @property denialReason Reason for denial (if denied)
     */
    data class PendingRequest(
        val requestId: String,
        val employeeId: String,
        val deviceId: String,
        val operation: String,
        val capabilities: Set<Capability>,
        val details: Map<String, String>,
        val createdAt: Instant,
        var status: AuthDto.RequestStatus = AuthDto.RequestStatus.PENDING,
        var approvedBy: String? = null,
        var approvedByName: String? = null,
        var denialReason: String? = null,
    ) {
        fun isExpired(): Boolean {
            // Requests expire after 3 minutes
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            val expiry = Instant.fromEpochMilliseconds(createdAt.toEpochMilliseconds() + 180_000) // 3 min
            return now > expiry
        }
    }

    /**
     * A deferred action waiting for approval before execution.
     *
     * @property requestId Links to the PendingRequest
     * @property employeeId Employee who triggered the action
     * @property deviceId Device where action originated
     * @property operation Human-readable operation name
     * @property actionExecutor Suspend lambda that executes the actual action and returns a result message
     */
    data class DeferredAction(
        val requestId: String,
        val employeeId: String,
        val deviceId: String,
        val operation: String,
        val actionExecutor: suspend () -> String, // Returns success message
    )

    private val deferredActions = ConcurrentHashMap<String, DeferredAction>()

    fun addDeferredAction(action: DeferredAction) {
        deferredActions[action.requestId] = action
        logger.info("Stored deferred action for request ${action.requestId}: ${action.operation}")
    }

    fun getDeferredAction(requestId: String): DeferredAction? = deferredActions[requestId]

    fun removeDeferredAction(requestId: String) {
        deferredActions.remove(requestId)
        logger.debug("Removed deferred action for request $requestId")
    }

    // ========== Session Management ==========

    /**
     * Add a new session.
     *
     * @param session Session information
     */
    fun addSession(session: SessionInfo) {
        // Remove any existing session on this device
        sessionsByDevice[session.deviceId]?.let { existingSessionId ->
            removeSession(existingSessionId)
        }

        sessionsByEmployee[session.requestingEmployeeId]?.let { sessionId ->
            removeSession(sessionId)
        }

        sessions[session.sessionId] = session
        sessionsByDevice[session.deviceId] = session.sessionId
        sessionsByEmployee[session.requestingEmployeeId] = session.sessionId
        logger.info("Created temporary session ${session.sessionId} for ${session.managerName} on device ${session.deviceId}")
    }

    /**
     * Get session by session ID.
     *
     * @param sessionId Session identifier
     * @return Session info or null if not found/expired
     */
    fun getSession(sessionId: String): SessionInfo? {
        val session = sessions[sessionId]
        return if (session != null && !session.isExpired()) {
            session
        } else {
            if (session != null) {
                expire(session) // Clean up expired session
            }
            null
        }
    }

    /**
     * Get active session for a device.
     *
     * @param deviceId Device identifier
     * @return Session info or null if none active
     */
    fun getSessionByDevice(deviceId: String): SessionInfo? {
        val sessionId = sessionsByDevice[deviceId] ?: return null
        return getSession(sessionId)
    }

    fun getSessionByRequestingEmployeeUuid(employeeUuid: String): SessionInfo? {
        val sessionId = sessionsByEmployee[employeeUuid] ?: return null
        return getSession(sessionId)
    }

    /**
     * The step-up in force for [employeeId] at [deviceId], or null when there is none.
     *
     * **This is the ONE definition of "an elevation is active here".** Two places ask the question
     * and they must never diverge:
     *
     * - `TokenClaimsElevation.elevatedCapabilities()` (pos-server), which decides what the
     *   authorization gate lets through;
     * - `ExceptionConverter`'s audit writes (this module), which record that a manager was involved.
     *
     * If those two disagreed, the audit trail would claim an elevation the gate never honoured, or
     * stay silent about one it did. Both call here instead of re-implementing the three conditions.
     *
     * A session belongs to **a person at a station**: the device alone is not enough, or a second
     * cashier at the same till — or a queued background call — would inherit it.
     *
     * @param employeeId The acting employee's UUID, exactly as the token carries it
     * @param deviceId The device the request arrived from, exactly as the token carries it
     * @return The active session, or null if there is none, it expired, or it belongs to someone else
     */
    fun activeElevationFor(
        employeeId: String?,
        deviceId: String?,
    ): SessionInfo? {
        if (employeeId == null || deviceId == null) return null
        val session = getSessionByDevice(deviceId) ?: return null
        if (session.isExpired()) return null
        if (session.requestingEmployeeId != employeeId) return null
        return session
    }

    /**
     * The station session under which [employeeId] — the MANAGER — acts at [deviceId], or null.
     *
     * On an order station a manager's override is attributed to the manager themselves
     * (`TokenClaims.overrideActorId()`), so the audit row's actor is the manager, and
     * [activeElevationFor] (keyed on the requesting station account) cannot find the session.
     * The audit writer asks this second question so the row still says `TEMPORARY_SESSION` with
     * the manager, rather than a `DIRECT` action by a manager who never logged in on that device.
     * Only for [SessionInfo.onStation] sessions: at a till the cashier stays the actor.
     */
    fun stationOverrideActedBy(
        employeeId: String?,
        deviceId: String?,
    ): SessionInfo? {
        if (employeeId == null || deviceId == null) return null
        val session = getSessionByDevice(deviceId) ?: return null
        if (!session.onStation || session.isExpired()) return null
        return session.takeIf { it.managerId == employeeId }
    }

    /**
     * Remove a session.
     *
     * @param sessionId Session identifier
     */
    fun removeSession(sessionId: String) {
        sessions.remove(sessionId)?.let { session ->
            sessionsByDevice.remove(session.deviceId)
            logger.info("Removed session $sessionId for device ${session.deviceId}")
        }
    }

    /**
     * Remove an expired session, remembering it in [cappedSessions] when the hard cap ended it.
     */
    private fun expire(session: SessionInfo) {
        if (session.endsAtHardCap()) {
            cappedSessions[session.sessionId] = session
        }
        removeSession(session.sessionId)
    }

    /**
     * The session [sessionId] if it ended because it reached [MAX_LIFETIME], else null.
     *
     * @param sessionId Session identifier
     */
    fun sessionEndedAtHardCap(sessionId: String): SessionInfo? = cappedSessions[sessionId]

    /**
     * Extend a session: its expiry becomes now + [SESSION_LENGTH], clipped to the hard cap.
     *
     * @param sessionId Session identifier
     * @return Updated session or null if not found (or already expired)
     */
    fun extendSession(sessionId: String): SessionInfo? {
        val session = getSession(sessionId) ?: return null
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val newExpiry = minOf(now.plus(SESSION_LENGTH), session.hardCapAt())

        val extended = session.copy(expiresAt = newExpiry)
        sessions[sessionId] = extended
        logger.info("Extended session $sessionId until $newExpiry")
        return extended
    }

    /**
     * Get all active (non-expired) temporary sessions.
     *
     * @return List of active sessions
     */
    fun getActiveSessions(): List<SessionInfo> = sessions.values.filter { !it.isExpired() }

    /**
     * Clean up expired sessions.
     */
    fun cleanupExpiredSessions() {
        val expiredSessions = sessions.filter { it.value.isExpired() }
        expiredSessions.forEach { (_, session) ->
            expire(session)
        }
        val forgetBefore = Instant.fromEpochMilliseconds(System.currentTimeMillis()).minus(CAPPED_RETENTION)
        cappedSessions.entries.removeIf { it.value.hardCapAt() < forgetBefore }
        if (expiredSessions.isNotEmpty()) {
            logger.debug("Cleaned up ${expiredSessions.size} expired sessions")
        }
    }

    // ========== Request Management ==========

    /**
     * Add a pending approval request.
     *
     * @param request Request information
     */
    fun addRequest(request: PendingRequest) {
        pendingRequests[request.requestId] = request
        logger.info("Created approval request ${request.requestId} for ${request.employeeId}: ${request.operation}")
    }

    /**
     * Get request by ID.
     *
     * @param requestId Request identifier
     * @return Request or null if not found
     */
    fun getRequest(requestId: String): PendingRequest? {
        val request = pendingRequests[requestId]
        return if (request != null && !request.isExpired()) {
            request
        } else {
            if (request != null && request.isExpired()) {
                request.status = AuthDto.RequestStatus.EXPIRED
            }
            request
        }
    }

    /**
     * Get all pending requests.
     *
     * @return List of pending requests
     */
    fun getPendingRequests(): List<PendingRequest> =
        pendingRequests.values.filter {
            it.status == AuthDto.RequestStatus.PENDING && !it.isExpired()
        }

    /**
     * Update request status.
     *
     * @param requestId Request identifier
     * @param status New status
     * @param managerId Manager ID (for approvals/denials)
     * @param managerName Manager name (for approvals/denials)
     * @param denialReason Reason for denial (if denied)
     */

    /**
     * Atomically transition request from PENDING to a new status.
     * Returns true if the transition succeeded, false if the request
     * was already in a non-PENDING state (e.g. already approved by another manager).
     */
    @Synchronized
    fun claimRequest(
        requestId: String,
        newStatus: AuthDto.RequestStatus,
        managerId: String? = null,
        managerName: String? = null,
        denialReason: String? = null,
    ): Boolean {
        val request = pendingRequests[requestId] ?: return false
        if (request.status != AuthDto.RequestStatus.PENDING) return false

        request.status = newStatus
        request.approvedBy = managerId
        request.approvedByName = managerName
        request.denialReason = denialReason
        logger.info("Claimed request $requestId: $newStatus by ${managerName ?: managerId ?: "system"}")
        return true
    }

    fun updateRequestStatus(
        requestId: String,
        status: AuthDto.RequestStatus,
        managerId: String? = null,
        managerName: String? = null,
        denialReason: String? = null,
    ) {
        pendingRequests[requestId]?.let { request ->
            request.status = status
            request.approvedBy = managerId
            request.approvedByName = managerName
            request.denialReason = denialReason
            logger.info("Updated request $requestId status to $status by ${managerName ?: "system"}")
        }
    }

    /**
     * Remove request from pending list.
     *
     * @param requestId Request identifier
     */

    /**
     * Claims every request whose 3-minute window has passed and hands them back, once.
     *
     * `PendingRequest.isExpired()` only ever ran when somebody happened to call [getRequest], and
     * nothing swept this map: an unanswered request stayed PENDING in memory and the requesting
     * device was never told. The till then cannot distinguish "still with the manager" from "nobody
     * ever answered" — the same silence a restart produces, and far more common
     * (E2E-2026-09-20 #8).
     *
     * Claiming through [claimRequest] is what makes the hand-back exactly-once: a request already
     * APPROVED, DENIED or EXPIRED is not PENDING, so a second sweep — or a manager deciding in the
     * same instant — returns nothing and the requester is notified once.
     *
     * @return the requests this call moved PENDING -> EXPIRED, for the caller to notify.
     */
    fun expireStaleRequests(): List<PendingRequest> =
        pendingRequests.values
            .filter { it.status == AuthDto.RequestStatus.PENDING && it.isExpired() }
            .filter { claimRequest(requestId = it.requestId, newStatus = AuthDto.RequestStatus.EXPIRED) }

    fun removeRequest(requestId: String) {
        pendingRequests.remove(requestId)
        logger.debug("Removed request $requestId")
    }

    /**
     * Clean up old requests (completed or expired).
     */
    fun cleanupOldRequests() {
        val oldRequests =
            pendingRequests.filter {
                val request = it.value
                request.isExpired() ||
                    request.status == AuthDto.RequestStatus.APPROVED ||
                    request.status == AuthDto.RequestStatus.DENIED
            }
        oldRequests.forEach { (requestId, _) ->
            removeRequest(requestId)
        }
        if (oldRequests.isNotEmpty()) {
            logger.debug("Cleaned up ${oldRequests.size} old requests")
        }
    }

    /**
     * Get statistics for monitoring.
     */
    fun getStats(): Map<String, Int> =
        mapOf(
            "activeSessions" to sessions.size,
            "pendingRequests" to getPendingRequests().size,
            "totalRequests" to pendingRequests.size,
        )
}
