package org.example.project.features.auth.domain.repository

import kotlinx.datetime.LocalDateTime

data class RefreshTokenRow(
    val jti: String,
    val tokenHash: String,
    val employeeId: String,
    val deviceId: String,
    val issuedAt: LocalDateTime,
    val expiresAt: LocalDateTime,
    val revokedAt: LocalDateTime?,
    val replacedByJti: String?,
)

interface RefreshTokenRepository {
    fun insert(
        jti: String,
        tokenHash: String,
        employeeId: String,
        deviceId: String,
        issuedAt: LocalDateTime,
        expiresAt: LocalDateTime,
    )

    fun findByJti(jti: String): RefreshTokenRow?

    fun markRevoked(
        jti: String,
        replacedByJti: String?,
        now: LocalDateTime,
    )

    fun revokeAllForEmployee(
        employeeId: String,
        now: LocalDateTime,
    ): Int

    fun deleteExpired(now: LocalDateTime): Int
}
