/**
 * Implementation of TemporarySessionService.
 *
 * Manages temporary manager sessions with PIN authentication and automatic
 * expiration. Coordinates with employee repository for authentication and
 * in-memory store for session management.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, DataError> in Stage B Batch 7.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.auth.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.example.project.core.auth.ElevationScope
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.error.PosErrorCode
import org.example.project.core.websocket.AuthNotificationManager
import org.example.project.features.auth.data.TemporarySessionStore
import org.example.project.features.auth.domain.service.TemporarySessionService
import org.example.project.features.employee.data.service.GroupCapabilityResolver
import org.example.project.features.employee.domain.repository.EmployeeRepository
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Temporary session service implementation.
 *
 * Handles session creation with PIN validation, session lifecycle management,
 * and device-specific session tracking.
 */
@OptIn(ExperimentalTime::class)
class TemporarySessionServiceImpl(
    private val employeeRepository: EmployeeRepository,
    private val groupCapabilityResolver: GroupCapabilityResolver,
) : TemporarySessionService {
    private val logger = LoggerFactory.getLogger(TemporarySessionServiceImpl::class.java)

    override suspend fun createSession(
        managerUsername: String,
        managerPin: String,
        deviceId: String,
        requestingEmployeeId: String,
        requestedCapability: String?,
        onStation: Boolean,
    ): AuthDto.TemporarySessionResponseDto {
        // managerRole captured from dbQuery for use after transaction completes
        var managerRole: EmployeeRole? = null

        // Rejected up front rather than intersected away later: naming a capability nobody may lend
        // is a mistake in the request, and answering it with a session that grants nothing would look
        // like success and leave the caller blocked with no explanation.
        val requested =
            requestedCapability?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                Capability.entries.firstOrNull { it.name == name }
                    ?: throw validationFailed("Unknown capability: $name")
            }
        if (requested != null && !ElevationScope.isLendable(requested)) {
            throw PosException(
                PosErrorCode.AUTH_FORBIDDEN,
                HttpStatusCode.Forbidden,
                "${requested.name} cannot be lent by a step-up. Only till overrides can.",
            )
        }
        // On an order station only the Storno and the transfer may be lent (NOTE-v): a station never
        // bills, prices or discounts, so a manager's sign-in there must not open those either.
        val scope = if (onStation) ElevationScope.STATION_OVERRIDES else ElevationScope.TILL_OVERRIDES
        if (requested != null && requested !in scope) {
            throw PosException(
                PosErrorCode.AUTH_FORBIDDEN,
                HttpStatusCode.Forbidden,
                "${requested.name} cannot be lent on an order station. Only the Storno and the transfer can.",
            )
        }

        // Resolved OUTSIDE dbQuery: the resolver opens its own transaction on a cache miss, and
        // nesting one inside the block below would be a nested transaction.
        val approverId = approverIdFor(managerUsername)
        val approverHolds = approverId?.let { groupCapabilityResolver.forEmployee(it) } ?: emptySet()
        val granted = ElevationScope.resolve(requested, approverHolds, scope)

        val response =
            dbQuery {
                // Validate input
                if (managerUsername.isBlank() || managerPin.isBlank() || deviceId.isBlank()) {
                    throw validationFailed("username, PIN, or deviceId is required")
                }

                // Get employee by username or employee number
                val identifier = managerUsername.trim()
                val employee =
                    employeeRepository.getEmployeeByUsername(identifier)
                        ?: employeeRepository.getEmployeeByEmployeeNumber(identifier)
                if (employee == null) {
                    logger.warn(
                        "createSession: failed to find employee with identifier={}",
                        identifier,
                    )
                    throw invalidCredentials()
                }

                // Verify PIN
                if (employee.pin != managerPin) {
                    logger.warn(
                        "createSession: invalid PIN for username={}",
                        managerUsername,
                    )
                    // Wrong PIN -> AUTH_INVALID_CREDENTIALS / 401 (NOT 403)
                    throw invalidCredentials()
                }

                // Check if account is active
                if (!employee.isActive) {
                    logger.warn(
                        "createSession: attempt to create session for inactive employee, username={}",
                        managerUsername,
                    )
                    throw employeeInactive()
                }

                // The approver must hold ELEVATION_APPROVE — no longer a role check.
                //
                // The old `employee.role.isManagerOrHigher()` failed in both directions: an
                // ACCOUNTANT carries role MANAGER and could authorise till overrides they have
                // nothing to do with, and a purpose-built shift-lead group never could, whatever an
                // operator granted it. V114 seeds the capability into exactly the groups that
                // satisfied the old check, so nothing changes on the day this deploys.
                if (Capability.ELEVATION_APPROVE !in approverHolds) {
                    logger.warn(
                        "createSession: employee cannot authorise a step-up, username={}",
                        managerUsername,
                    )
                    // Authenticated but not allowed -> AUTH_FORBIDDEN / 403
                    throw notAnApprover(managerUsername)
                }

                // An approver who holds none of what was asked for cannot help. Creating the session
                // anyway would lend nothing while reporting success — the cashier would re-enter the
                // PIN, watch it succeed, and stay blocked.
                if (granted.isEmpty()) {
                    logger.warn(
                        "createSession: approver {} holds none of the requested authority ({})",
                        managerUsername,
                        requested?.name ?: "till overrides",
                    )
                    throw cannotLend(managerUsername, requested?.name)
                }

                // Create session
                val sessionId = "SESSION-${UUID.randomUUID()}"
                val now = Clock.System.now()
                val expiresAt = now.plus(TemporarySessionStore.SESSION_LENGTH)

                // Get employee name from info relationship
                val employeeInfo = employee.employeeInfo
                val managerName =
                    if (employeeInfo != null) {
                        "${employeeInfo.firstName} ${employeeInfo.lastName}"
                    } else {
                        employee.username // Fallback to username if info not available
                    }

                val sessionInfo =
                    TemporarySessionStore.SessionInfo(
                        sessionId = sessionId,
                        managerId = employee.id.value.toString(),
                        managerName = managerName,
                        managerRole = employee.role,
                        deviceId = deviceId,
                        requestingEmployeeId = requestingEmployeeId,
                        grantedCapabilities = granted,
                        createdAt = now,
                        expiresAt = expiresAt,
                        onStation = onStation,
                    )

                TemporarySessionStore.addSession(sessionInfo)

                // Capture before leaving dbQuery (Exposed lazy loading)
                managerRole = employee.role

                logger.info(
                    "createSession: created temporary session for managerName={}, deviceId={}",
                    managerName,
                    deviceId,
                )

                sessionInfo.toResponseDto()
            }

        // After dbQuery completes — deliver queued messages outside transaction scope
        managerRole?.let { role ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    logger.info(
                        "Delivering queued messages to temp session device: employeeId={}, role={}",
                        requestingEmployeeId,
                        role,
                    )

                    AuthNotificationManager.deliverQueuedApprovalsToTemporarySession(requestingEmployeeId)
                } catch (e: Exception) {
                    logger.error(
                        "Failed to deliver queued messages to temp session device: {}",
                        requestingEmployeeId,
                        e,
                    )
                }
            }
        }

        return response
    }

    override suspend fun validateSession(
        sessionId: String,
        deviceId: String,
    ): String {
        val session =
            TemporarySessionStore.getSession(sessionId)
                ?: run {
                    logger.debug(
                        "validateSession: session not found or expired, sessionId={}",
                        sessionId,
                    )
                    throw sessionExpired(sessionId)
                }

        if (session.deviceId != deviceId) {
            logger.warn(
                "validateSession: session does not belong to device, sessionId={}, deviceId={}",
                sessionId,
                deviceId,
            )
            // Token presented for wrong device -> credential mismatch, 401
            throw invalidCredentials()
        }

        if (session.isExpired()) {
            logger.debug("validateSession: session has expired, sessionId={}", sessionId)
            TemporarySessionStore.removeSession(sessionId)
            throw sessionExpired(sessionId)
        }

        return session.managerId
    }

    override suspend fun extendSession(
        sessionId: String,
        requestingEmployeeId: String,
    ): AuthDto.TemporarySessionResponseDto {
        val live =
            TemporarySessionStore.getSession(sessionId)
                ?: throw refusalForMissing(sessionId, requestingEmployeeId)
        requireOwnedBy(live, requestingEmployeeId)
        val extended =
            TemporarySessionStore.extendSession(sessionId)
                ?: run {
                    logger.debug(
                        "extendSession: session not found for extension, sessionId={}",
                        sessionId,
                    )
                    throw sessionNotFound(sessionId)
                }

        logger.info(
            "extendSession: extended session sessionId={} for managerName={} until {} (hard cap {})",
            sessionId,
            extended.managerName,
            extended.expiresAt,
            extended.hardCapAt(),
        )

        return extended.toResponseDto()
    }

    /**
     * Why an extend found no live session: the hard cap ended it (409, the PIN is needed again), or
     * it expired, was ended, or never existed (404). A session that ran into its cap stays owned —
     * a colleague asking about it still gets 403.
     */
    private fun refusalForMissing(
        sessionId: String,
        requestingEmployeeId: String,
    ): PosException {
        val capped = TemporarySessionStore.sessionEndedAtHardCap(sessionId) ?: return sessionNotFound(sessionId)
        requireOwnedBy(capped, requestingEmployeeId)
        logger.info("extendSession: refused, session {} reached its hard cap at {}", sessionId, capped.hardCapAt())
        return PosException(
            PosErrorCode.AUTH_STEP_UP_CAP_REACHED,
            HttpStatusCode.Conflict,
            "The manager approval reached its ${TemporarySessionStore.MAX_LIFETIME.inWholeMinutes}-minute limit; " +
                "the manager must enter the PIN again",
        )
    }

    override suspend fun endSession(
        sessionId: String,
        requestingEmployeeId: String,
    ) {
        // Already expired or ended: the lent capability is gone either way, which is what was asked.
        val live = TemporarySessionStore.getSession(sessionId) ?: return
        requireOwnedBy(live, requestingEmployeeId)
        TemporarySessionStore.removeSession(sessionId)
        logger.info("endSession: manually ended session sessionId={}", sessionId)
    }

    /**
     * A step-up belongs to the employee it was lent to. Anyone else — including a colleague at the
     * same till — may neither prolong nor cancel it.
     */
    private fun requireOwnedBy(
        session: TemporarySessionStore.SessionInfo,
        requestingEmployeeId: String,
    ) {
        if (session.requestingEmployeeId != requestingEmployeeId) {
            logger.warn("temporary session {} addressed by an employee it was not lent to", session.sessionId)
            throw PosException(
                PosErrorCode.AUTH_FORBIDDEN,
                HttpStatusCode.Forbidden,
                "This temporary session was not lent to you",
            )
        }
    }

    override suspend fun getActiveSession(deviceId: String): AuthDto.TemporarySessionResponseDto? =
        TemporarySessionStore.getSessionByDevice(deviceId)?.toResponseDto()

    private fun TemporarySessionStore.SessionInfo.toResponseDto() =
        AuthDto.TemporarySessionResponseDto(
            sessionId = sessionId,
            managerId = managerId,
            managerName = managerName,
            managerRole = managerRole.name,
            deviceId = deviceId,
            requestingEmployeeId = requestingEmployeeId,
            expiresAt = expiresAt.toString(),
            remainingSeconds = remainingSeconds(),
            hardCapAt = hardCapAt().toString(),
        )

    // --- PosException helpers ---

    private fun invalidCredentials() =
        PosException(
            PosErrorCode.AUTH_INVALID_CREDENTIALS,
            HttpStatusCode.Unauthorized,
            "Invalid credentials",
        )

    /**
     * 404, not 401: the caller's own token is fine, only the step-up is gone. A 401 here would read
     * to the till like its login had lapsed.
     */
    private fun sessionNotFound(sessionId: String) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Temporary session $sessionId expired or not found",
        )

    private fun sessionExpired(sessionId: String) =
        PosException(
            PosErrorCode.AUTH_SESSION_EXPIRED,
            HttpStatusCode.Unauthorized,
            "Temporary session $sessionId expired or not found",
        )

    /**
     * The approver's employee id, or null if the identifier matches nobody.
     *
     * A separate lookup from the one inside `createSession`'s transaction, purely so the capability
     * resolver can run outside it. Returning null here is not the rejection — the transaction still
     * does its own lookup and throws `invalidCredentials()`, which is what keeps a probe for valid
     * usernames from being answered differently than a wrong PIN.
     */
    private suspend fun approverIdFor(identifier: String): UUID? =
        dbQuery {
            val trimmed = identifier.trim()
            val employee =
                employeeRepository.getEmployeeByUsername(trimmed)
                    ?: employeeRepository.getEmployeeByEmployeeNumber(trimmed)
            employee?.id?.value
        }

    private fun notAnApprover(username: String) =
        PosException(
            PosErrorCode.AUTH_FORBIDDEN,
            HttpStatusCode.Forbidden,
            "Employee $username cannot authorise a step-up",
        )

    private fun cannotLend(
        username: String,
        capability: String?,
    ) = PosException(
        PosErrorCode.AUTH_FORBIDDEN,
        HttpStatusCode.Forbidden,
        "Employee $username does not hold ${capability ?: "any till override"} and cannot lend it",
    )

    private fun employeeInactive() =
        PosException(
            PosErrorCode.EMPLOYEE_INACTIVE,
            HttpStatusCode.Forbidden,
            "Employee account is inactive",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
