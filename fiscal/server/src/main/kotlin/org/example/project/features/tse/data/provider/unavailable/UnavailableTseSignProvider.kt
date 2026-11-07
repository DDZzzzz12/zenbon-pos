/**
 * Signing provider used when LOCAL mode is configured but the Swissbit TSE SDK is
 * absent from the runtime classpath.
 *
 * Every signing call throws [PosException] with [PosErrorCode.TSE_UNREACHABLE] — the
 * same shape an offline device produces. Call sites already catch PosException into
 * `TseSignOutcome.Failed`, which
 * [org.example.project.features.tse.domain.TseSigningOutcomeResolver] turns into
 * QUEUE (Nachsignieren) or FAIL according to the shop's TseFailureBehavior. So a
 * missing SDK degrades along the existing, KassenSichV-compliant outage path instead
 * of killing the server at startup.
 *
 * Deliberately does NOT report a simulated signature: NORMAL must never silently
 * downgrade to SIMULATION, because that would put fake signatures on real receipts.
 *
 * @since 1.0.0
 */
package org.example.project.features.tse.data.provider.unavailable

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.infrastructure.errors.PosException
import org.slf4j.LoggerFactory

/**
 * Inert [TseSignProvider] for a LOCAL configuration with no SDK installed.
 */
class UnavailableTseSignProvider : TseSignProvider {
    private val logger = LoggerFactory.getLogger(UnavailableTseSignProvider::class.java)

    override suspend fun quickSign(
        processType: String,
        processData: String,
        clientId: String,
    ): TseDto.SignResultDto = throw sdkMissing("quickSign")

    override suspend fun startTransaction(
        processType: String,
        clientId: String,
    ): TseDto.TransactionStartResultDto = throw sdkMissing("startTransaction")

    override suspend fun updateTransaction(
        transactionNumber: Long,
        processData: String,
        clientId: String,
    ): TseDto.SignResultDto = throw sdkMissing("updateTransaction")

    override suspend fun finishTransaction(
        transactionNumber: Long,
        processData: String,
        clientId: String,
        processType: String,
    ): TseDto.SignResultDto = throw sdkMissing("finishTransaction")

    /** Null means "not reachable" — the contract the health check already uses. */
    override suspend fun checkHealth(): TseDto.HealthCheckResultDto? = null

    override fun isConnected(): Boolean = false

    /**
     * Reports the configured mode verbatim rather than claiming SIMULATION, so nothing
     * downstream mistakes an SDK-less server for a working simulator.
     */
    override fun getCachedMode(): String = "UNAVAILABLE"

    override fun registerConnectionListener() {
        logger.warn("TSE SDK missing — no connection listener registered; TSE stays unavailable until the SDK is installed")
    }

    override suspend fun restartConnection(): TseDto.HealthCheckResultDto? {
        logger.warn("TSE SDK missing — restartConnection is a no-op; install native/WormAPI.jar and restart the server")
        return null
    }

    override fun close() = Unit

    private fun sdkMissing(operation: String): PosException {
        logger.error("TSE $operation refused: Swissbit SDK not installed (expected at native/WormAPI.jar)")
        return PosException(
            PosErrorCode.TSE_UNREACHABLE,
            HttpStatusCode.FailedDependency,
            "TSE SDK is not installed. Place the Swissbit WormAPI.jar at native/WormAPI.jar " +
                "next to pos-server.jar and restart the server.",
        )
    }
}
