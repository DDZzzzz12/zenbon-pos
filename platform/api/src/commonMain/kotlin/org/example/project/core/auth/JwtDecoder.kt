/**
 * JWT token decoder for client-side token parsing.
 *
 * Decodes JWT tokens to extract employee role and permissions without
 * full signature verification (which is handled by the server).
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * JWT payload containing user claims.
 *
 * Represents the decoded JWT payload with employee information,
 * role, permissions, and device identification.
 */
@Serializable
data class JwtPayload(
    @SerialName("sub")
    val employeeId: String,
    @SerialName("employeeNumber")
    val employeeNumber: String,
    @SerialName("role")
    val role: String? = null,
    @SerialName("deviceId")
    val deviceId: String? = null,
    @SerialName("tokenType")
    val tokenType: String = "access",
    @SerialName("iat")
    val issuedAt: Long? = null,
    @SerialName("exp")
    val expiresAt: Long? = null,
)

/**
 * JWT decoder for extracting claims from access tokens.
 *
 * Provides multiplatform-compatible JWT parsing without external dependencies.
 * Note: This does NOT verify the signature - it only decodes the payload.
 * Signature verification is handled by the server.
 */
@OptIn(ExperimentalTime::class)
object JwtDecoder {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /**
     * Decode a JWT access token and extract the payload.
     *
     * This method decodes the JWT token structure (header.payload.signature)
     * and extracts the payload portion containing user claims.
     *
     * WARNING: This does NOT verify the token signature. The signature is
     * verified by the server. This is purely for extracting information from
     * tokens we trust came from our server.
     *
     * @param token JWT token string
     * @return Decoded JwtPayload or null if parsing fails
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun decode(token: String): JwtPayload? {
        return try {
            // JWT format: header.payload.signature
            val parts = token.split(".")
            if (parts.size != 3) {
                return null
            }

            // Decode the payload (second part)
            val payload = parts[1]

            // JWT uses Base64 URL encoding (without padding)
            // We need to add padding if necessary
            val paddedPayload =
                when (payload.length % 4) {
                    2 -> "$payload=="
                    3 -> "$payload="
                    else -> payload
                }

            // Decode from Base64
            val decoded = Base64.UrlSafe.decode(paddedPayload)
            val payloadJson = decoded.decodeToString()

            // Parse JSON to JwtPayload
            json.decodeFromString<JwtPayload>(payloadJson)
        } catch (e: Exception) {
            // Log error if needed, but don't crash
            println("Failed to decode JWT: ${e.message}")
            null
        }
    }

    /**
     * Extract employee role from JWT token — from the `role` claim `JwtConfig` actually issues.
     *
     * This used to read a `permissionEnums` claim and match strings like `"ADMIN_ROLE"`, falling
     * back to `TERMINAL_DEVICE`. That claim was retired with the per-employee grant mechanism and
     * `JwtConfig.generateAccessToken` never wrote those values anyway — it writes `employeeNumber`,
     * `role`, `deviceId` and `tokenType`. So the match could not succeed and **every** token
     * resolved to `TERMINAL_DEVICE`, the lowest role, whoever presented it.
     *
     * Nothing depended on the wrong answer — `PermissionManager.readRoleSnapshot` uses
     * `extractUserInfo` only as a validity gate and takes the role from local storage — which is why
     * it went unnoticed. Reading the claim that exists makes the decoder honest rather than
     * accidentally harmless.
     *
     * The `TERMINAL_DEVICE` fallback is kept for a token carrying no recognisable role, so behaviour
     * is never *less* defined than before: the least-privileged answer, not the most.
     *
     * @param token JWT token string
     * @return EmployeeRole, or null if the token could not be decoded at all
     */
    fun extractRole(token: String): EmployeeRole? {
        val payload = decode(token) ?: return null
        return EmployeeRole.entries.firstOrNull { it.name == payload.role } ?: EmployeeRole.TERMINAL_DEVICE
    }

    /**
     * Extract all relevant user information from JWT token.
     *
     * @param token JWT token string
     * @return UserInfo object or null if token is invalid
     */
    fun extractUserInfo(token: String): UserInfo? {
        val payload = decode(token) ?: return null
        val role = extractRole(token) ?: return null

        return UserInfo(
            employeeId = payload.employeeId,
            employeeNumber = payload.employeeNumber,
            role = role,
            deviceId = payload.deviceId ?: "unknown",
        )
    }

    /**
     * Check if a token is expired.
     *
     * @param token JWT token string
     * @return true if token is expired or invalid
     */
    fun isTokenExpired(token: String): Boolean {
        val payload = decode(token) ?: return true
        val expiresAt = payload.expiresAt ?: return true

        // Convert seconds to milliseconds and compare with current time
        val currentTimeSeconds = Clock.System.now().epochSeconds
        return currentTimeSeconds >= expiresAt
    }

    /**
     * Get time until token expiration in seconds.
     *
     * @param token JWT token string
     * @return Seconds until expiration, or 0 if expired/invalid
     */
    fun getTimeUntilExpiration(token: String): Long {
        val payload = decode(token) ?: return 0L
        val expiresAt = payload.expiresAt ?: return 0L

        val currentTimeSeconds = Clock.System.now().epochSeconds
        return (expiresAt - currentTimeSeconds).coerceAtLeast(0L)
    }

    /**
     * Extract device ID from JWT token.
     *
     * @param token JWT token string
     * @return Device ID or null if token is invalid
     */
    fun getDeviceId(token: String): String? {
        val payload = decode(token) ?: return null
        return payload.deviceId
    }
}

/**
 * User information extracted from JWT token.
 *
 * Contains all relevant user data for permission checking and UI display.
 */
data class UserInfo(
    val employeeId: String,
    val employeeNumber: String,
    val role: EmployeeRole,
    val deviceId: String,
)
