package org.example.project.features.auth.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.error.PosErrorCode
import org.example.project.core.websocket.NotificationHub
import org.example.project.features.auth.data.sha256Hex
import org.example.project.features.auth.domain.repository.RefreshTokenRepository
import org.example.project.features.auth.domain.repository.RefreshTokenRow
import org.example.project.features.auth.domain.service.RefreshTokenService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.getTimeZone
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class RefreshTokenServiceImpl(
    private val repository: RefreshTokenRepository,
    private val cutoffSupplier: () -> Long,
) : RefreshTokenService {
    private val logger = LoggerFactory.getLogger(RefreshTokenServiceImpl::class.java)

    private fun invalid(detail: String) = PosException(PosErrorCode.AUTH_INVALID_CREDENTIALS, HttpStatusCode.Unauthorized, detail)

    private fun Instant.local() = toLocalDateTime(getTimeZone())

    override suspend fun recordIssued(
        jti: String,
        rawToken: String,
        employeeId: String,
        deviceId: String,
        issuedAt: Instant,
        expiresAt: Instant,
    ) {
        dbQuery { repository.insert(jti, sha256Hex(rawToken), employeeId, deviceId, issuedAt.local(), expiresAt.local()) }
    }

    override suspend fun assertRefreshable(
        jti: String,
        rawToken: String,
        deviceId: String,
        employeeId: String,
    ) {
        val row = dbQuery { repository.findByJti(jti) } ?: throw invalid("Unknown refresh token")
        if (row.revokedAt != null) {
            if (isConcurrentRefresh(row, rawToken, deviceId)) {
                logger.info("Refresh-token grace reuse accepted for employee={} jti={}", row.employeeId, jti)
                return
            }
            if (row.replacedByJti != null) {
                logger.warn("Refresh-token reuse detected for employee={} jti={}; revoking family", employeeId, jti)
                dbQuery { repository.revokeAllForEmployee(row.employeeId, currentTimeStamp()) }
                closeStreams(row.employeeId)
            }
            throw invalid("Refresh token revoked")
        }
        if (row.tokenHash != sha256Hex(rawToken)) throw invalid("Refresh token mismatch")
        if (row.deviceId != deviceId) throw invalid("Refresh token device mismatch")
        val issuedEpoch = row.issuedAt.toInstant(getTimeZone()).epochSeconds
        if (issuedEpoch < cutoffSupplier()) throw invalid("Session expired by policy")
    }

    /**
     * Two browser tabs reloading together present the same refresh cookie: the first request
     * rotates it, and the second arrives a moment later with a token that is, strictly, already
     * used. Treating that as theft would log the employee out on every device.
     *
     * Allowed only for a token revoked BY ROTATION (a logout or admin revoke leaves no successor),
     * with the right bytes, from the same device, within [REUSE_GRACE] of the rotation (a negative
     * elapsed time — DST fall-back or a westward shop-zone change — is never inside it), and not past
     * the session-policy cutoff.
     *
     * The rotation chain starting at [row] must also still end in a LIVE token. A revoke-all
     * (logout everywhere, password change, PIN reset, reuse revocation) only stamps rows that are
     * still live, so a token rotated out moments before keeps its successor pointer. Without the
     * chain check, replaying it within the window would mint a fresh live token and silently undo
     * the revocation.
     */
    private suspend fun isConcurrentRefresh(
        row: RefreshTokenRow,
        rawToken: String,
        deviceId: String,
    ): Boolean {
        val revokedAt = row.revokedAt ?: return false
        val successorJti = row.replacedByJti ?: return false
        if (row.tokenHash != sha256Hex(rawToken) || row.deviceId != deviceId) return false
        val zone = getTimeZone()
        if (row.issuedAt.toInstant(zone).epochSeconds < cutoffSupplier()) return false
        val elapsed = currentTimeStamp().toInstant(zone) - revokedAt.toInstant(zone)
        if (elapsed < Duration.ZERO || elapsed > REUSE_GRACE) return false
        return dbQuery { rotationChainEndsLive(successorJti) }
    }

    /**
     * Follows `replacedByJti` successors from [firstSuccessorJti] and reports whether one of them is
     * still live. A missing successor, a successor revoked without a successor of its own (logout,
     * admin revoke, revoke-all), or a chain longer than [MAX_ROTATION_CHAIN_HOPS] counts as dead.
     * Repository access only: call inside `dbQuery`.
     */
    private fun rotationChainEndsLive(firstSuccessorJti: String): Boolean {
        var jti = firstSuccessorJti
        repeat(MAX_ROTATION_CHAIN_HOPS) {
            val successor = repository.findByJti(jti) ?: return false
            if (successor.revokedAt == null) return true
            jti = successor.replacedByJti ?: return false
        }
        return false
    }

    override suspend fun rotate(
        oldJti: String,
        newJti: String,
        newRawToken: String,
        employeeId: String,
        deviceId: String,
        issuedAt: Instant,
        expiresAt: Instant,
    ) {
        dbQuery {
            // A grace-window replay rotates an already-revoked token again. Re-stamping revokedAt
            // there would restart the window on every replay and keep a stolen token alive for good.
            if (repository.findByJti(oldJti)?.revokedAt == null) {
                repository.markRevoked(oldJti, replacedByJti = newJti, now = currentTimeStamp())
            }
            repository.insert(newJti, sha256Hex(newRawToken), employeeId, deviceId, issuedAt.local(), expiresAt.local())
        }
    }

    override suspend fun revokeByJti(jti: String) {
        dbQuery { repository.markRevoked(jti, replacedByJti = null, now = currentTimeStamp()) }
    }

    override suspend fun revokeAllForEmployee(employeeId: String) {
        dbQuery { repository.revokeAllForEmployee(employeeId, currentTimeStamp()) }
        // Authoritative session teardown: also close any live SSE notification stream(s)
        // for this employee. Revoking only the refresh tokens leaves the stream open until
        // the access token expires (~15 min), during which a logged-out / revoked session
        // keeps receiving pushed notifications.
        closeStreams(employeeId)
    }

    /** Close every live SSE connection for [employeeId]; logs the count when non-zero. */
    private fun closeStreams(employeeId: String) {
        val closed = NotificationHub.disconnectEmployee(employeeId)
        if (closed > 0) {
            logger.info("Closed {} SSE connection(s) for revoked employee={}", closed, employeeId)
        }
    }

    override suspend fun cleanupExpired(): Int = dbQuery { repository.deleteExpired(currentTimeStamp()) }

    private companion object {
        val REUSE_GRACE = 10.seconds

        /** Far more rotations than two tabs can produce inside [REUSE_GRACE]; bounds the walk. */
        const val MAX_ROTATION_CHAIN_HOPS = 8
    }
}
