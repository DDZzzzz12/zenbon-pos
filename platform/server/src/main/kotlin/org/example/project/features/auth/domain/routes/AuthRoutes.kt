/**
 * Authentication routes for login, logout, and token management.
 *
 * Provides HTTP endpoints for user authentication, JWT token generation,
 * token refresh, and session management.
 *
 * Migrated to throw-based error handling (Stage B Batch 7). Routes throw
 * PosException directly; the global StatusPages plugin converts to
 * application/problem+json. EmployeeService is also throws-style as of
 * Stage B Batch 10 — direct calls, no bridge.
 *
 * Auth-aware status mapping:
 * - Unknown user / wrong password / bad token -> AUTH_INVALID_CREDENTIALS / 401
 * - Refresh token expired / invalid type      -> AUTH_INVALID_CREDENTIALS / 401
 * - Missing/invalid JWT claims on protected   -> AUTH_INVALID_CREDENTIALS / 401
 * - Blank login fields                        -> VALIDATION_FAILED / 400
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
@file:OptIn(kotlin.time.ExperimentalTime::class)

package org.example.project.features.auth.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.example.project.auth.data.LoginRequest
import org.example.project.auth.data.LoginResponse
import org.example.project.auth.data.TokenResponse
import org.example.project.auth.data.ValidationResponse
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.JwtConfig
import org.example.project.core.security.heldCapabilities
import org.example.project.features.auth.domain.service.RefreshTokenService
import org.example.project.features.employee.data.service.GroupCapabilityResolver
import org.example.project.features.employee.domain.repository.PermissionGroupRepository
import org.example.project.features.employee.domain.service.EmployeeService
import org.example.project.features.terminal.data.service.BrowserStationPolicy
import org.example.project.features.terminal.data.service.TerminalCapabilityResolver
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getTokenClaims
import org.example.project.utils.dbQuery
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("AuthRoutes")

/**
 * Configures authentication-related API routes.
 *
 * Sets up endpoints for login, logout, token refresh, and user info
 * with proper JWT token generation and validation.
 */
fun Routing.authRoutes() {
    val employeeService by inject<EmployeeService>()
    val refreshTokenService by inject<RefreshTokenService>()
    val groupCapabilityResolver by inject<GroupCapabilityResolver>()
    val permissionGroups by inject<PermissionGroupRepository>()
    val capabilityResolver by inject<TerminalCapabilityResolver>()
    val browserStationPolicy by inject<BrowserStationPolicy>()

    // No terminal row (admin-web-*, "unknown") means no browser-station check.
    suspend fun requireBrowserStationAllowed(deviceId: String) {
        val cap = capabilityResolver.byDeviceId(deviceId) ?: return
        browserStationPolicy.requireAllowed(cap.clientKind, TerminalRole.fromCode(cap.role))
    }

    route("/api/auth") {
        /**
         * Log in with employee credentials.
         *
         * Description: Public endpoint. Exchanges an identifier (username or employee number) and secret for an access/refresh token pair plus the employee's profile. The `X-Device-ID` header binds the refresh token to the calling device; requests without it are recorded as device `unknown`. Wrong credentials always answer 401; correct credentials for an employee with no permission group answer 403 `NO_PERMISSION_GROUP`. With `X-Auth-Transport: cookie` the refresh token is set as the `__Host-pos_refresh` HttpOnly cookie and omitted from the body.
         *
         * Tag: auth
         * Response: 400 identifier or secret is blank.
         * Response: 401 Unknown identifier, or the secret does not match.
         * Response: 403 NO_PERMISSION_GROUP — the credentials are right but the employee has no permission group.
         */
        post("/login") {
            val loginRequest = call.receive<LoginRequest>()
            logger.info("Login attempt for identifier: ${loginRequest.identifier}")
            val deviceId = call.request.headers["X-Device-ID"] ?: "unknown"

            if (loginRequest.identifier.isBlank() || loginRequest.secret.isBlank()) {
                throw PosException(
                    PosErrorCode.VALIDATION_FAILED,
                    HttpStatusCode.BadRequest,
                    "Identifier and secret are required",
                )
            }

            val employee =
                employeeService.authenticateEmployee(loginRequest.identifier, loginRequest.secret)
                    ?: run {
                        logger.warn("Failed login attempt for identifier: ${loginRequest.identifier}")
                        throw invalidCredentials()
                    }

            requireBrowserStationAllowed(deviceId)
            logger.info("Using shop for user: ${employee.username}")
            call.respondIssuedSession(
                generateTokensAndRespond(deviceId, employee, refreshTokenService, groupCapabilityResolver, permissionGroups, replacingJti = null),
            )
        }

        /**
         * Exchange a refresh token for a new token pair.
         *
         * Description: Public endpoint. Rotates the refresh token — the presented token is consumed and a fresh pair is issued, bound to the `X-Device-ID` header. Replaying an already-rotated token revokes the whole token family, except a replay from the same device within 10 seconds of the rotation while its rotation chain still ends in a live token and the session-policy cutoff has not passed (concurrent refreshes from two browser tabs), which is accepted. A deactivated employee has all their tokens revoked on the attempt. With `X-Auth-Transport: cookie` the token is read from the `__Host-pos_refresh` HttpOnly cookie and the rotated one is returned in that cookie instead of the body; without the header the body transport is unchanged.
         *
         * Tag: auth
         * Response: 400 refreshToken is missing or blank (body transport).
         * Response: 401 The token is expired, invalid, not a refresh token, already rotated, bound to another device or employee, or the employee is gone or inactive, or cookie transport was requested without a refresh cookie.
         * Response: 403 NO_PERMISSION_GROUP — the employee no longer has a permission group.
         */
        post("/refresh") {
            call.expireRefreshCookieOnRejection {
                val deviceId = call.request.headers["X-Device-ID"] ?: "unknown"
                val presentedToken = call.presentedRefreshToken()

                val tokenClaims =
                    JwtConfig.validateToken(presentedToken)
                        ?: throw invalidCredentials("Invalid refresh token")
                if (tokenClaims.tokenType != "refresh") {
                    throw invalidCredentials("Invalid refresh token")
                }

                // Validate the refresh token against persisted state (reuse detection,
                // device/employee binding); throws PosException(401) if invalid and revokes
                // the token family on reuse.
                val oldJti = tokenClaims.jti ?: throw invalidCredentials("Invalid refresh token")
                refreshTokenService.assertRefreshable(oldJti, presentedToken, deviceId, tokenClaims.employeeId)
                requireBrowserStationAllowed(deviceId)

                // Employee-not-found here is treated as AUTH_INVALID_CREDENTIALS (refresh
                // token references a user that no longer exists / was disabled — surface
                // as 401 to the client, which will logout).
                val employee =
                    try {
                        employeeService.getEmployeeById(UUID.fromString(tokenClaims.employeeId))
                    } catch (e: PosException) {
                        throw invalidCredentials("User not found")
                    }

                // Reject refresh for a non-active employee (defense in depth; deactivation
                // also revokes tokens elsewhere). Revoke the family on rejection.
                if (!employee.isActive) {
                    refreshTokenService.revokeAllForEmployee(tokenClaims.employeeId)
                    throw invalidCredentials("Account is not active")
                }

                logger.info("Token refresh for user: ${employee.username} on device: ${tokenClaims.deviceId}")
                call.respondIssuedSession(
                    generateTokensAndRespond(deviceId, employee, refreshTokenService, groupCapabilityResolver, permissionGroups, replacingJti = oldJti),
                )
            }
        }

        // Authenticated endpoints requiring JWT token
        authenticate("auth-jwt") {
            /**
             * Validate the current access token.
             *
             * Description: Cheap liveness check for the client's stored token — it hits no database. Reaching the handler already means the JWT plugin accepted the token, so the response echoes the employee id from the claims.
             *
             * Tag: auth
             * Response: 401 Missing or invalid bearer token.
             */
            get("/validate") {
                val claims =
                    call.getTokenClaims()
                        ?: throw invalidCredentials("Invalid token")
                call.respond(
                    HttpStatusCode.OK,
                    ValidationResponse(
                        valid = true,
                        employeeNumber = claims.employeeId,
                    ),
                )
            }

            /**
             * Log out the current employee.
             *
             * Description: Revokes every refresh token the employee holds — on all devices, not just this one — and closes their live SSE notification streams. Idempotent: a token without usable claims still answers 200. Also expires the `__Host-pos_refresh` cookie.
             *
             * Tag: auth
             * Response: 401 Missing or invalid bearer token.
             */
            post("/logout") {
                val claims = call.getTokenClaims()
                if (claims != null) {
                    // revokeAllForEmployee also closes the employee's live SSE stream(s).
                    refreshTokenService.revokeAllForEmployee(claims.employeeId)
                    logger.info("Logout: revoked sessions for employee=${claims.employeeNumber}")
                } else {
                    logger.warn("Logout called without valid claims; treating as no-op")
                }
                // Harmless for native clients, which hold no cookie.
                call.expireRefreshCookie()
                call.respond(HttpStatusCode.OK, "Logged out successfully")
            }

            /**
             * Get the current employee's profile.
             *
             * Description: Resolves the JWT's employee id against the employee record and returns the identity the POS renders in its header — id, username, employee number, role and training-mode flag.
             *
             * Tag: auth
             * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
             * Response: 404 The token references an employee that no longer exists.
             */
            get("/me") {
                val claims =
                    call.getTokenClaims()
                        ?: throw invalidCredentials("Invalid token")

                val employeeId = UUID.fromString(claims.employeeId)
                val employee = employeeService.getEmployeeById(employeeId)

                // The capability set the server will actually enforce, resolved from the employee's
                // permission GROUP — not derived from `role`. Since Stage 3 the two can legitimately
                // disagree: an accountant carries role MANAGER and group ACCOUNTANT, so a client
                // gating on role alone would offer them a manager's screens and collect 403s.
                //
                // The station mask applies here too, and must: `heldCapabilities()` is what the
                // route gate resolved for THIS request, and for a station JWT that is already the
                // masked set (see TerminalMasks). Reporting the unmasked group instead would offer
                // an order station controls the server then refuses.
                //
                // Reads `heldCapabilities()` — what the route gate resolved for THIS request —
                // rather than re-resolving the group. The two differ during a step-up: the gate
                // unions in what the session lends, and a client that gated its UI on the group
                // alone would keep the lent controls disabled while the server accepted them.
                //
                // This used to be right by accident. Elevation swapped the token's employeeId, so
                // "resolve the caller's group" resolved the approving manager's — which also meant
                // this endpoint reported someone else's identity to the client.
                call.respond(
                    HttpStatusCode.OK,
                    EmployeeInfoDto.EmployeeInfo(
                        id = employee.id,
                        username = employee.username,
                        employeeNumber = employee.employeeNumber,
                        role = employee.role,
                        trainingMode = employee.trainingMode,
                        capabilities = call.heldCapabilities().map { it.name }.toSet(),
                        permissionGroupCode = employee.permissionGroupCode,
                        permissionGroupName = permissionGroups.displayNameOf(employee.permissionGroupCode),
                    ),
                )
            }
        }
    }
}

/**
 * An employee with no permission group holds no capability, so a session for them would 403 on every
 * call (and a client reading the empty set as "unknown" would show a role-shaped console). Refuse the
 * sign-in instead, with a code the client can explain. Runs only AFTER the credentials were verified
 * (login) or the refresh token accepted, so an unknown identifier still answers 401 and nothing about
 * group membership leaks to someone who does not hold the secret. Device/terminal tokens do not pass
 * through here: they resolve their capabilities from the terminal's own group.
 */
private fun requirePermissionGroup(employee: EmployeeDto.EmployeeResponseDto) {
    if (employee.permissionGroupCode != null) return
    logger.warn("Sign-in refused: employee {} has no permission group", employee.username)
    throw PosException(
        PosErrorCode.NO_PERMISSION_GROUP,
        HttpStatusCode.Forbidden,
        "No permission group is assigned to this account",
    )
}

private fun invalidCredentials(detail: String = "Invalid credentials") =
    PosException(
        PosErrorCode.AUTH_INVALID_CREDENTIALS,
        HttpStatusCode.Unauthorized,
        detail,
    )

/**
 * Helper function to generate tokens and respond with login success.
 *
 * Generates both access and refresh tokens using the client's device ID from X-Device-ID header.
 * This device ID is consistent per device (generated by DeviceInfoProvider on client).
 *
 * @param employee Employee information for token generation
 * @return TokenResponse containing access token, refresh token, and expiration time
 */
private suspend fun generateTokensAndRespond(
    deviceId: String,
    employee: EmployeeDto.EmployeeResponseDto,
    refreshTokenService: RefreshTokenService,
    groupCapabilityResolver: GroupCapabilityResolver,
    permissionGroups: PermissionGroupRepository,
    replacingJti: String?,
): LoginResponse {
    requirePermissionGroup(employee)
    logger.debug("Generating tokens for ${employee.username} with device ID: $deviceId")

    val accessToken =
        JwtConfig.generateAccessToken(
            employeeId = employee.id,
            employeeNumber = employee.employeeNumber,
            role = employee.role,
            deviceId = deviceId,
        )

    val refreshToken =
        JwtConfig.generateRefreshToken(
            employeeId = employee.id,
            deviceId = deviceId,
        )

    val rc =
        JwtConfig.validateToken(refreshToken)
            ?: throw PosException(
                PosErrorCode.INTERNAL_ERROR,
                HttpStatusCode.InternalServerError,
                "Token generation failed",
            )
    val newJti =
        rc.jti ?: throw PosException(
            PosErrorCode.INTERNAL_ERROR,
            HttpStatusCode.InternalServerError,
            "Token missing jti",
        )
    val issuedAt = rc.issuedAt!!
    val expiresAt = rc.expiresAt!!

    if (replacingJti == null) {
        refreshTokenService.recordIssued(newJti, refreshToken, employee.id, deviceId, issuedAt, expiresAt)
    } else {
        refreshTokenService.rotate(replacingJti, newJti, refreshToken, employee.id, deviceId, issuedAt, expiresAt)
    }

    val expiresIn = JwtConfig.getExpirationTime()

    val tokenResponse =
        TokenResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = expiresIn,
        )

    logger.info("Successful token generation for user: ${employee.username} on device: $deviceId")

    return LoginResponse(
        token = tokenResponse,
        employee =
            EmployeeInfoDto.EmployeeInfo(
                id = employee.id,
                username = employee.username,
                employeeNumber = employee.employeeNumber,
                role = employee.role,
                language = employee.languagePreference,
                trainingMode = employee.trainingMode,
                // Returned with the login response as well as from `/api/auth/me`, so a client is
                // never in the state of being logged in without knowing what it may do — which would
                // mean either a blank UI or a round-trip's worth of role-based guessing.
                capabilities =
                    runCatching { UUID.fromString(employee.id) }
                        .getOrNull()
                        ?.let { groupCapabilityResolver.forEmployee(it).map { cap -> cap.name }.toSet() }
                        .orEmpty(),
                permissionGroupCode = employee.permissionGroupCode,
                permissionGroupName = permissionGroups.displayNameOf(employee.permissionGroupCode),
            ),
    )
}

/**
 * The stored display name of the group [code] names, or null for no group (or a code that no
 * longer resolves). Reported beside the code so a client can label an operator-made group, whose
 * name it has no translation for, without needing PERMISSION_ADMIN to list the groups (R6-4).
 */
private suspend fun PermissionGroupRepository.displayNameOf(code: String?): String? = code?.let { groupCode -> dbQuery { findGroup(groupCode)?.name } }
