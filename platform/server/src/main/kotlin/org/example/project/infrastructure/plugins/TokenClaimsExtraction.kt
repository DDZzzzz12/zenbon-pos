@file:OptIn(ExperimentalTime::class)

package org.example.project.infrastructure.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.security.JwtConfig
import org.example.project.core.security.TokenClaims
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.dao.*
import org.slf4j.LoggerFactory
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private val logger = LoggerFactory.getLogger("Security")

/**
 * Creates a development user with full permissions.
 *
 * **IMPORTANT:** This should only be called in development mode.
 * Creates an admin user with all permissions for testing purposes.
 *
 * @param username Username for the dev user
 * @return Created Employee entity or null if creation fails
 */
private fun createDevUser(username: String): Employee? =
    try {
        logger.info("Development mode: Creating dev user '$username' with ADMIN role and all permissions")

        // Find the highest employee number to generate a unique one
        val newEmployeeNumber = "DEV-001"

        val devUser =
            Employee.new {
                this.username = username
                this.employeeNumber = newEmployeeNumber
                this.role = EmployeeRole.ADMIN
                this.passwordHash = "" // No password needed in dev mode
                this.pin = "0000" // Default PIN for dev
            }

        // ...and put them IN the ADMIN group. `Employee.new` writes the row directly, and the only
        // code that assigns a permission group is EmployeeRepositoryImpl — so without this the dev
        // user held NO capabilities and every `Cap`-gated route answered 403.
        //
        // That is not a small window: Stage 1 turned ~181 previously-`Authenticated` rules into
        // `Cap`, so dev mode authenticated you, logged "role=ADMIN", and then refused essentially
        // the whole API. `GroupCapabilityResolver` fails closed by design, which is right — the
        // missing piece was here.
        //
        // A role group's code IS the role name, which is what makes this one call enough.

        EmployeeRepositoryImpl().setPermissionGroupByCode(devUser.id.value, EmployeeRole.ADMIN.name)

        logger.info("Development user created: username=$username, employeeNumber=$newEmployeeNumber, role=ADMIN")
        devUser
    } catch (e: Exception) {
        logger.error("Failed to create development user '$username'", e)
        null
    }

/**
 * Extension function to get token claims from the current call.
 *
 * @return TokenClaims if authentication is present and valid, null otherwise
 */
suspend fun ApplicationCall.getTokenClaims(): TokenClaims? {
    logger.debug("getTokenClaims called. Development mode: ${JwtConfig.isDevelopmentMode}")

    // Reuse the claims RouteAuthorizationPlugin already decoded and verified for this request, if
    // it got there first. Skipping the second HMAC verification matters because Stage 1 put that
    // decode on the POS hot path; the value is only ever stashed after a successful
    // JwtConfig.validateToken, so this is not a way around verification.
    if (attributes.contains(verifiedClaimsKey)) return attributes[verifiedClaimsKey]

    if (JwtConfig.isDevelopmentMode) {
        logger.debug("Development mode enabled - checking for UserIdPrincipal")
        val userPrincipal = principal<UserIdPrincipal>()
        logger.debug("UserIdPrincipal: $userPrincipal")

        // If we have a real JWT token (not dev-bypass), skip development mode and use JWT
        val jwtPrincipal = principal<JWTPrincipal>()
        if (jwtPrincipal != null) {
            logger.debug("JWT principal found even in dev mode - using JWT authentication")
            // Fall through to JWT processing below
        } else {
            // Only use development mode if there's no JWT
            val username = userPrincipal?.name ?: "dev-user" // Default dev user
            logger.debug("No JWT principal, using dev mode with username: $username")

            val user =
                dbQuery {
                    Employee.find { EmployeeTable.username eq username }.firstOrNull()
                        ?: createDevUser(username)
                }

            return if (user != null) {
                val devDeviceId = "dev-device-$username"

                TokenClaims(
                    employeeId = user.id.toString(),
                    employeeNumber = user.employeeNumber,
                    role = user.role.name,
                    deviceId = devDeviceId,
                    tokenType = "access",
                    issuedAt = Instant.fromEpochMilliseconds(0),
                    expiresAt = Instant.fromEpochMilliseconds(Long.MAX_VALUE),
                )
            } else {
                logger.error("Development mode: Failed to find or create user '$username'")
                null
            }
        }
    }

    val principal = principal<JWTPrincipal>()
    logger.debug("JWT Principal: ${if (principal != null) "found" else "null"}")
    if (principal == null) {
        // No JWT — fall back to a login-less device principal (X-Device-Token auth, A4 Task 5/6).
        return getDeviceTokenClaims()
    }

    // Extract deviceId from JWT token (server-generated, cryptographically signed)
    val deviceId = principal.payload.getClaim("deviceId")?.asString() ?: "unknown"
    logger.debug("Device ID extracted from token: $deviceId")

    val role = principal.payload.getClaim("role")?.asString()
    val enumRole = EmployeeRole.getRole(role!!)

    return TokenClaims(
        employeeId = principal.payload.subject ?: "",
        employeeNumber = principal.payload.getClaim("employeeNumber").asString(),
        role = enumRole.name,
        deviceId = deviceId,
        tokenType = principal.payload.getClaim("tokenType")?.asString() ?: "access",
        issuedAt =
            principal.payload.issuedAt?.toInstant()?.let {
                Instant.fromEpochMilliseconds(it.toEpochMilli())
            },
        expiresAt =
            principal.payload.expiresAt?.toInstant()?.let {
                Instant.fromEpochMilliseconds(it.toEpochMilli())
            },
        station = principal.payload.getClaim("station")?.asString(),
    )
}

/**
 * Synthesizes [TokenClaims] for a login-less order station authenticated via [DeviceTokenPrincipal]
 * (Terminal Phase A4, Task 5/6). Attributes the request to the single shared [TerminalEmployee]
 * (role [EmployeeRole.TERMINAL_DEVICE]) so `createOrder` has an `employeeId` to record. Returns null if no device principal is present either (unauthenticated).
 *
 * A step-up never touches this path. It used to be dangerous that it might: elevation rewrote the
 * token's identity and was keyed on the physical device id alone, so on a SHARED station every
 * concurrent device-principal request would have been promoted to whichever manager stepped up on
 * some other terminal with the same device id — privilege escalation plus fiscal-attribution
 * corruption, since `order.employee` would have been re-attributed too.
 *
 * That whole class of mistake is gone. Elevation no longer rewrites identity (it unions
 * capabilities onto the requester's own set) and is keyed on the device AND the requesting
 * employee, so it cannot attach to a principal it was not granted to. A login-less station's
 * principal still resolves to the terminal-employee, which carries no employee-scoped session.
 */
private suspend fun ApplicationCall.getDeviceTokenClaims(): TokenClaims? {
    val devicePrincipal = principal<DeviceTokenPrincipal>() ?: return null
    val cap = devicePrincipal.cap

    // CRITICAL: the PHYSICAL device id, NOT clientId — OrderServiceImpl.createOrder resolves the
    // terminal via findByDeviceId(deviceId), which matches the device_id column. Passing
    // cap.clientId (TER-xxx) here would return null -> CARD_TERMINAL_NOT_FOUND.
    val deviceId = cap.deviceId ?: error("bound terminal ${cap.clientId} has no deviceId")

    return dbQuery {
        val employee = TerminalEmployee.requireRow()
        TokenClaims(
            employeeId = employee.id.toString(),
            employeeNumber = employee.employeeNumber,
            role = EmployeeRole.TERMINAL_DEVICE.name,
            deviceId = deviceId,
            // Must agree with the JWT branch above, which sets station from the "station" JWT
            // claim — cap.role IS that value here, and TerminalMasks narrows on claims.station
            // regardless of which path produced it.
            station = cap.role,
            tokenType = "device",
            issuedAt = null,
            expiresAt = null,
        )
    }
}

/**
 * Extract employee ID from JWT token.
 *
 * Retrieves the employeeNumber claim from the authenticated JWT principal.
 * Returns null if no principal found or claim is missing.
 *
 * Usage in routes:
 * ```kotlin
 * authenticate("auth-jwt") {
 *     post("/api/products") {
 *         val userId = call.getEmployeeIdFromJwt()
 *         if (userId != null) {
 *             withCurrentUser(userId) {
 *                 service.createProduct(request)
 *             }
 *         }
 *     }
 * }
 * ```
 */
suspend fun ApplicationCall.getEmployeeIdFromJwt(): String =
    try {
        this.getTokenClaims()?.employeeId ?: throw UnauthorizedException("Missing employeeId from JWT token")
    } catch (e: Exception) {
        throw UnauthorizedException("Failed to extract employeeId from JWT token")
    }
