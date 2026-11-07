package org.example.project.features.auth.database.tables

import org.example.project.features.employee.database.tables.EmployeeTable
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Server-side record of issued refresh tokens. The unit of revocation:
 * logout/rotation/revoke-all mark rows revoked; the refresh endpoint rejects
 * any token whose row is missing, revoked, hash/device-mismatched, or pre-cutoff.
 */
object RefreshTokenTable : UUIDTable("refresh_tokens", "id") {
    val jti = varchar("jti", 36).uniqueIndex("ux_refresh_tokens_jti")
    val tokenHash = varchar("token_hash", 64)
    val employeeId = reference("employee_id", EmployeeTable).index("ix_refresh_tokens_employee")
    val deviceId = varchar("device_id", 255)
    val issuedAt = datetime("issued_at")
    val expiresAt = datetime("expires_at").index("ix_refresh_tokens_expires")
    val revokedAt = datetime("revoked_at").nullable()
    val replacedByJti = varchar("replaced_by_jti", 36).nullable()
    val lastUsedAt = datetime("last_used_at").nullable()
}
