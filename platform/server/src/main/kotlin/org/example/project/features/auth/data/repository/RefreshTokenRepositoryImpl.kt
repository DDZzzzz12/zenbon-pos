package org.example.project.features.auth.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.auth.database.tables.RefreshTokenTable
import org.example.project.features.auth.domain.repository.RefreshTokenRepository
import org.example.project.features.auth.domain.repository.RefreshTokenRow
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.util.UUID

/**
 * Concrete implementation of RefreshTokenRepository using Exposed ORM (raw Table DSL).
 *
 * Returns plain data rows; never throws PosException and never opens its own
 * transaction (dbQuery is owned by the service layer).
 */
class RefreshTokenRepositoryImpl : RefreshTokenRepository {
    override fun insert(
        jti: String,
        tokenHash: String,
        employeeId: String,
        deviceId: String,
        issuedAt: LocalDateTime,
        expiresAt: LocalDateTime,
    ) {
        RefreshTokenTable.insert {
            it[RefreshTokenTable.id] = UUID.randomUUID()
            it[RefreshTokenTable.jti] = jti
            it[RefreshTokenTable.tokenHash] = tokenHash
            it[RefreshTokenTable.employeeId] = UUID.fromString(employeeId)
            it[RefreshTokenTable.deviceId] = deviceId
            it[RefreshTokenTable.issuedAt] = issuedAt
            it[RefreshTokenTable.expiresAt] = expiresAt
        }
    }

    override fun findByJti(jti: String): RefreshTokenRow? =
        RefreshTokenTable.selectAll().where { RefreshTokenTable.jti eq jti }.singleOrNull()?.let { r ->
            RefreshTokenRow(
                jti = r[RefreshTokenTable.jti],
                tokenHash = r[RefreshTokenTable.tokenHash],
                employeeId = r[RefreshTokenTable.employeeId].value.toString(),
                deviceId = r[RefreshTokenTable.deviceId],
                issuedAt = r[RefreshTokenTable.issuedAt],
                expiresAt = r[RefreshTokenTable.expiresAt],
                revokedAt = r[RefreshTokenTable.revokedAt],
                replacedByJti = r[RefreshTokenTable.replacedByJti],
            )
        }

    override fun markRevoked(
        jti: String,
        replacedByJti: String?,
        now: LocalDateTime,
    ) {
        RefreshTokenTable.update({ RefreshTokenTable.jti eq jti }) {
            it[RefreshTokenTable.revokedAt] = now
            it[RefreshTokenTable.lastUsedAt] = now
            if (replacedByJti != null) it[RefreshTokenTable.replacedByJti] = replacedByJti
        }
    }

    override fun revokeAllForEmployee(
        employeeId: String,
        now: LocalDateTime,
    ): Int =
        RefreshTokenTable.update({
            (RefreshTokenTable.employeeId eq UUID.fromString(employeeId)) and RefreshTokenTable.revokedAt.isNull()
        }) {
            it[RefreshTokenTable.revokedAt] = now
        }

    override fun deleteExpired(now: LocalDateTime): Int = RefreshTokenTable.deleteWhere { RefreshTokenTable.expiresAt less now }
}
