package org.example.project.auth.data

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.employee.EmployeeInfoDto

@Serializable
data class LoginResponse(
    val token: TokenResponse,
    val employee: EmployeeInfoDto.EmployeeInfo,
)

@Serializable
data class ValidationResponse(
    val valid: Boolean,
    val username: String? = null,
    val employeeNumber: String? = null,
    val message: String? = null,
)

/**
 * Login request DTO.
 */
@Serializable
data class LoginRequest(
    val identifier: String,
    val secret: String,
) {
    fun validate() {
        require(identifier.isNotBlank()) { "Identifier is required" }
        require(secret.isNotBlank()) { "Secret is required" }
    }
}

/**
 * Token refresh request DTO.
 */
@Serializable
data class RefreshTokenRequest(
    /** Null under cookie transport: the token then travels as the `__Host-pos_refresh` cookie. */
    val refreshToken: String? = null,
)

/**
 * Token refresh response DTO.
 */
@Serializable
data class TokenResponse(
    val accessToken: String,
    /** Null under cookie transport — never in a body the browser's JavaScript can read. */
    val refreshToken: String? = null,
    val tokenType: String = "Bearer",
    val expiresIn: Long, // expires in seconds
)
