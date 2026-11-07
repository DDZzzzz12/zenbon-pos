package org.example.project.core.security

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import org.koin.ktor.ext.inject

/**
 * Custom exception for unauthorized access.
 */
class UnauthorizedException(
    message: String,
) : Exception(message)

/**
 * Extract employee ID from JWT token claims.
 *
 * @return Employee ID string from the authenticated JWT principal
 * @throws UnauthorizedException if no valid token claims are found
 */
suspend fun ApplicationCall.getEmployeeIdFromJwt(): String =
    try {
        val provider by inject<TokenClaimsProvider>()
        provider.getTokenClaims(this)?.employeeId
            ?: throw UnauthorizedException("Missing employeeId from JWT token")
    } catch (e: UnauthorizedException) {
        throw e
    } catch (e: Exception) {
        throw UnauthorizedException("Failed to extract employeeId from JWT token")
    }

/**
 * Get token claims from the current call via the TokenClaimsProvider.
 *
 * @return TokenClaims if authentication is present and valid, null otherwise
 */
suspend fun ApplicationCall.getTokenClaims(): TokenClaims? {
    val provider by inject<TokenClaimsProvider>()
    return provider.getTokenClaims(this)
}

/*
 * The role and permission GATE family that used to live below here is gone:
 * `isManagerOrHigher`, `hasRole`, `hasAnyRole`, `hasPermission`, `hasAnyPermission`,
 * `requirePermissionOrRole`, `requireAnyPermission`.
 *
 * Every route is gated by `RoutePermissions` on a capability, and `RoutePermissionsCoverageTest`
 * proves none escapes it. Keeping these was actively harmful:
 *
 * - `hasAnyRole` here never received the ADMIN escape that `hasRole` has. Stage 1 fixed that
 *   inconsistency (defect D3) in the pos-server copy of the same family and not in this one — two
 *   copies of an authorization helper, one of them wrong, which is the whole argument against
 *   duplicating them.
 * - `hasPermission`/`hasAnyPermission` answered from `claims.permissions`, which Stage 2a made
 *   permanently empty when it retired per-employee grants. They could only ever return false while
 *   reading like a real check.
 * - A role gate now contradicts the group model: an AUDITOR holds ZBON_MANAGE but may carry role
 *   CASHIER, so a role check would refuse them at a route their group admits.
 *
 * `UnauthorizedException`, `getEmployeeIdFromJwt` and `getTokenClaims` above are the parts that were
 * ever really used, and they stay.
 */
