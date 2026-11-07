package org.example.project.core.security

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Represents the claims extracted from a JWT token.
 *
 * Used by route handlers to access authenticated user information
 * without depending on the full JWT/Security infrastructure.
 *
 * @property employeeId UUID of the authenticated employee
 * @property employeeNumber Human-readable employee number
 * @property permissions Set of granted permissions
 * @property role Employee role name (ADMIN, MANAGER, CASHIER)
 * @property deviceId Server-generated unique device identifier
 * @property tokenType Type of token (access or refresh)
 * @property jti JWT ID - unique token identifier for blacklist support
 * @property issuedAt When the token was issued
 * @property expiresAt When the token expires
 * @property station Terminal role name when this token was minted for a login-less station
 */
@Serializable
data class TokenClaims(
    val employeeId: String,
    val employeeNumber: String,
    val role: String,
    val deviceId: String,
    val tokenType: String,
    val jti: String? = null,
    val issuedAt: Instant?,
    val expiresAt: Instant?,
    /**
     * The `station` claim: the `TerminalRole` name of the login-less station this token was minted
     * for (`"ORDER_STATION"`), or null for a person's token. Set only by the station-session
     * exchange (`POST /api/v1/terminals/session`), never by login or refresh; the route plugin masks
     * a token carrying it with that station's `TerminalMasks` entry.
     */
    val station: String? = null,
)
