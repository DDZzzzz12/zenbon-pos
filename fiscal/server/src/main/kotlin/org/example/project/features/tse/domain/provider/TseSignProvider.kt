/**
 * PROTECTED MODULE — Do not modify without explicit authorization.
 * TSE operations are fiscally regulated (KassenSichV) and changes require careful review.
 *
 * Provider interface for TSE signing operations.
 *
 * Abstracts the signing communication path. The only topology is direct local
 * hardware access via the embedded server-tse module.
 *
 * Implementation:
 * - [org.example.project.features.tse.data.provider.local.LocalTseSignProvider]
 *
 * @since 1.0.0
 */
package org.example.project.features.tse.domain.provider

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.infrastructure.errors.PosException

/**
 * Provider interface for TSE transaction signing operations.
 *
 * All signing methods throw [PosException] on failure (B1 amendment).
 * The error code is one of the TSE_* entries in
 * [org.example.project.core.error.PosErrorCode] per the cross-walk table.
 */
interface TseSignProvider {
    /**
     * Quick sign a transaction (start + finish in one call).
     *
     * @param processType Process type (e.g., "Kassenbeleg-V1")
     * @param processData Complete DSFinV-K formatted process data
     * @param clientId TSE client ID for the signing terminal
     * @return SignResultDto on success
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun quickSign(
        processType: String,
        processData: String,
        clientId: String,
    ): TseDto.SignResultDto

    /**
     * Starts a new TSE transaction.
     *
     * @param processType Process type (e.g., "Bestellung-V1")
     * @param clientId TSE client ID for the signing terminal
     * @return TransactionStartResultDto on success
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun startTransaction(
        processType: String,
        clientId: String,
    ): TseDto.TransactionStartResultDto

    /**
     * Updates an ongoing TSE transaction with new process data.
     *
     * @param transactionNumber TSE transaction number from start
     * @param processData New process data in DSFinV-K format
     * @param clientId TSE client ID for the signing terminal
     * @return SignResultDto on success
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun updateTransaction(
        transactionNumber: Long,
        processData: String,
        clientId: String,
    ): TseDto.SignResultDto

    /**
     * Finishes a started transaction. [processType] is signed with it: without it every finish
     * went out as Kassenbeleg-V1, so an order finished this way would have been mislabelled
     * (planning finding, 2026-09-22).
     */
    @Throws(PosException::class)
    suspend fun finishTransaction(
        transactionNumber: Long,
        processData: String,
        clientId: String,
        processType: String,
    ): TseDto.SignResultDto

    /**
     * Checks if the TSE is reachable and healthy.
     *
     * @return HealthCheckResultDto if connected, null if not reachable
     */
    suspend fun checkHealth(): TseDto.HealthCheckResultDto?

    /**
     * Gets the current connection status.
     *
     * @return true if TSE is connected and operational
     */
    fun isConnected(): Boolean

    /**
     * Gets the cached TSE operating mode (NORMAL, SIMULATION, DISABLED).
     *
     * @return Cached mode string
     */
    fun getCachedMode(): String

    /**
     * Registers a WebSocket connection listener for TSE status broadcasts.
     *
     * Should be called once during application startup.
     */
    fun registerConnectionListener()

    /**
     * Restarts the TSE connection.
     *
     * Resets mode to NORMAL, resumes retry service, and performs health check.
     *
     * @return Health check result if connection succeeded, null otherwise
     */
    suspend fun restartConnection(): TseDto.HealthCheckResultDto?

    /**
     * Closes the provider and releases resources.
     */
    fun close()
}
