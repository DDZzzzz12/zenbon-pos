/**
 * Delegating implementation of [TseSignProvider] that enables hot-swapping
 * the underlying concrete provider at runtime.
 *
 * Holds a [Volatile] reference to the current delegate and forwards all method calls.
 * When the TSE connection mode changes (REMOTE ↔ LOCAL), [switchDelegate] is called
 * to close the old provider and install the new one without server restart.
 *
 * Thread Safety:
 * - The delegate reference is @Volatile for visibility across threads
 * - [switchDelegate] is synchronized to prevent concurrent swaps
 * - In-flight operations on the old delegate may fail during swap (acceptable for config changes)
 *
 * @see org.example.project.features.tse.domain.provider.TseSignProvider
 * @since 1.0.0
 */
package org.example.project.features.tse.data.provider

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.slf4j.LoggerFactory

class DelegatingTseSignProvider(
    initial: TseSignProvider,
) : TseSignProvider {
    private val logger = LoggerFactory.getLogger(DelegatingTseSignProvider::class.java)

    @Volatile
    private var delegate: TseSignProvider = initial

    /**
     * Swaps the underlying provider implementation.
     *
     * Closes the old delegate and installs the new one.
     *
     * @param newDelegate The new concrete TseSignProvider to delegate to
     */
    @Synchronized
    fun switchDelegate(newDelegate: TseSignProvider) {
        val old = delegate
        delegate = newDelegate
        logger.info("TseSignProvider switched from ${old::class.simpleName} to ${newDelegate::class.simpleName}")
        try {
            old.close()
        } catch (e: Exception) {
            logger.warn("Error closing old TseSignProvider: ${e.message}")
        }
    }

    override suspend fun quickSign(
        processType: String,
        processData: String,
        clientId: String,
    ): TseDto.SignResultDto = delegate.quickSign(processType, processData, clientId)

    override suspend fun startTransaction(
        processType: String,
        clientId: String,
    ): TseDto.TransactionStartResultDto = delegate.startTransaction(processType, clientId)

    override suspend fun updateTransaction(
        transactionNumber: Long,
        processData: String,
        clientId: String,
    ): TseDto.SignResultDto = delegate.updateTransaction(transactionNumber, processData, clientId)

    override suspend fun finishTransaction(
        transactionNumber: Long,
        processData: String,
        clientId: String,
        processType: String,
    ): TseDto.SignResultDto = delegate.finishTransaction(transactionNumber, processData, clientId, processType)

    override suspend fun checkHealth(): TseDto.HealthCheckResultDto? = delegate.checkHealth()

    override fun isConnected(): Boolean = delegate.isConnected()

    override fun getCachedMode(): String = delegate.getCachedMode()

    override fun registerConnectionListener() = delegate.registerConnectionListener()

    override suspend fun restartConnection(): TseDto.HealthCheckResultDto? = delegate.restartConnection()

    override fun close() = delegate.close()
}
