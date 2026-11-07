package org.example.project.features.auth.domain.service

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
interface RefreshTokenService {
    suspend fun recordIssued(
        jti: String,
        rawToken: String,
        employeeId: String,
        deviceId: String,
        issuedAt: Instant,
        expiresAt: Instant,
    )

    suspend fun assertRefreshable(
        jti: String,
        rawToken: String,
        deviceId: String,
        employeeId: String,
    )

    suspend fun rotate(
        oldJti: String,
        newJti: String,
        newRawToken: String,
        employeeId: String,
        deviceId: String,
        issuedAt: Instant,
        expiresAt: Instant,
    )

    suspend fun revokeByJti(jti: String)

    suspend fun revokeAllForEmployee(employeeId: String)

    suspend fun cleanupExpired(): Int
}
