/**
 * API routes for temporary manager session management.
 *
 * Provides endpoints for creating, querying, extending, and terminating
 * device-specific manager sessions that grant temporary elevated permissions.
 *
 * All routes require JWT authentication. Sessions are 5 minutes by default
 * and can be extended or manually terminated.
 *
 * Migrated to throw-based error handling (Stage B Batch 7). Service throws
 * PosException; the global StatusPages plugin converts to application/problem+json.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.auth.domain.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.TokenClaims
import org.example.project.features.auth.domain.service.TemporarySessionService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getTokenClaims
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

/**
 * Configure temporary session routes.
 *
 * Endpoints:
 * - POST /api/auth/temporary-session - Create new session with manager credentials
 * - GET /api/auth/temporary-session/active - Get active session for current device
 * - PUT /api/auth/temporary-session/{sessionId}/extend - Extend session by 5 minutes
 * - DELETE /api/auth/temporary-session/{sessionId} - Manually end session
 */
fun Route.temporarySessionRoutes() {
    val service by inject<TemporarySessionService>()
    val logger = LoggerFactory.getLogger("TemporarySessionRoutes")

    authenticate("auth-jwt") {
        route("/api/auth/temporary-session") {
            /**
             * Open a temporary manager session on this device.
             *
             * Description: A manager types their username and PIN on a cashier's device to grant it elevated permissions for five minutes without a full re-login. On an order station (a station session token) this is the manager's temporary sign-in: the lend is limited to the item Storno and the item/table transfer, and those actions are attributed to the manager, not the station account. The session is bound to the supplied `deviceId`, and the employee whose token made the call is recorded as the requester. An unknown username or wrong PIN answers 401; a known, authenticated employee who is not a manager answers 403.
             *
             * Tag: temporary-session
             * Response: 400 managerUsername, managerPin or deviceId is blank.
             * Response: 401 Missing or invalid bearer token, or the manager's credentials do not match.
             * Response: 403 The named employee is inactive, or is not a manager; or the caller is an order station that sent a bare device token instead of its station session, or a deviceId other than its own; or the requested capability is not one a station may be lent.
             */
            post {
                val request = call.receive<AuthDto.TemporarySessionRequestDto>()

                // Extract requesting employee's ID from JWT claims
                val claims = call.getTokenClaims()
                val requestingEmployeeId = claims?.employeeId ?: ""
                val onStation = claims?.station != null
                if (onStation) requireStationSignInBinding(claims!!, request.deviceId)

                logger.info(
                    "Creating temporary session for manager: {} on device: {}, requested by employee: {}",
                    request.managerUsername,
                    request.deviceId,
                    requestingEmployeeId,
                )

                val response =
                    service.createSession(
                        managerUsername = request.managerUsername,
                        managerPin = request.managerPin,
                        deviceId = request.deviceId,
                        requestingEmployeeId = requestingEmployeeId,
                        requestedCapability = request.requestedCapability,
                        onStation = onStation,
                    )
                logger.info("Temporary session created: {}", response.sessionId)
                call.respond(HttpStatusCode.Created, response)
            }

            /**
             * Get this device's active temporary session.
             *
             * Description: The device is identified by the required `X-Device-ID` header. Answers 204 with no body when the device has no live session, so a client can poll it to decide whether elevated controls stay unlocked.
             *
             * Tag: temporary-session
             * Response: 204 No temporary session is active on this device.
             * Response: 400 The X-Device-ID header is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/active") {
                val deviceId = call.request.headers["X-Device-ID"]
                if (deviceId.isNullOrBlank()) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "Missing X-Device-ID header",
                    )
                }

                val sessionData = service.getActiveSession(deviceId)
                if (sessionData != null) {
                    logger.debug("Active session found: {}", sessionData.sessionId)
                    call.respond(HttpStatusCode.OK, sessionData)
                } else {
                    logger.debug("No active session for device: {}", deviceId)
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            /**
             * Extend a temporary session.
             *
             * Description: Moves the session's expiry to now plus the length a fresh step-up gets — never further, and never past the hard cap of 15 minutes after the manager entered the PIN (`hardCapAt` in the response) — and returns the updated session. Only the employee the session was lent to may extend it; an expired session cannot be revived and needs the manager's PIN again.
             *
             * Tag: temporary-session
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 The session was lent to a different employee.
             * Response: 404 The session expired, was ended, or never existed.
             * Response: 409 AUTH_STEP_UP_CAP_REACHED — the session reached its hard cap and has ended; the manager must enter the PIN again.
             */
            put("/{sessionId}/extend") {
                val sessionId = call.requireSessionId()
                val response = service.extendSession(sessionId, call.callerEmployeeId())
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * End a temporary session.
             *
             * Description: Ends the step-up at once — the lent capability stops working on the very next request, instead of living on until the session would have expired. Only the employee the session was lent to may end it. Ending a session that already expired or was ended answers 204 as well.
             *
             * Tag: temporary-session
             * Response: 204 The session is ended.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 The session was lent to a different employee.
             */
            delete("/{sessionId}") {
                val sessionId = call.requireSessionId()
                service.endSession(sessionId, call.callerEmployeeId())
                logger.info("Temporary session ended: {}", sessionId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun ApplicationCall.requireSessionId(): String =
    parameters["sessionId"]?.takeIf { it.isNotBlank() }
        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "sessionId is required")

private suspend fun ApplicationCall.callerEmployeeId(): String =
    getTokenClaims()?.employeeId
        ?: throw PosException(PosErrorCode.AUTH_FORBIDDEN, HttpStatusCode.Unauthorized, "Invalid or missing authentication token")

/**
 * An order station's manager sign-in must come from the station's own session and its own device.
 *
 * The elevation is honoured only for a JWT (the route plugin never elevates a bare device-token
 * request), so a session opened from the device token would lend nothing while looking like
 * success. And the session is keyed on the body's deviceId, so a station naming another device
 * would plant a session there.
 */
private fun requireStationSignInBinding(
    claims: TokenClaims,
    deviceId: String,
) {
    if (claims.tokenType == "device") {
        throw PosException(
            PosErrorCode.AUTH_FORBIDDEN,
            HttpStatusCode.Forbidden,
            "A station manager sign-in needs the station session token, not the device token",
        )
    }
    if (deviceId != claims.deviceId) {
        throw PosException(
            PosErrorCode.AUTH_FORBIDDEN,
            HttpStatusCode.Forbidden,
            "A station may open a manager sign-in only for its own device",
        )
    }
}
