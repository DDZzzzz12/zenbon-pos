
package org.example.project.features.tse.domain.factory

import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.tse.data.service.DisabledTseTransactionServiceImpl
import org.example.project.features.tse.data.service.RealTseTransactionServiceImpl
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.features.tse.domain.repository.TseTransactionRepository
import org.example.project.features.tse.domain.service.TseTransactionService

/**
 * Simple TSE Service Factory for creating appropriate TSE services.
 *
 * Clean, straightforward factory pattern for TSE service creation
 * based on shop configuration.
 *
 * Architecture:
 * - SIMULATION and NORMAL modes both use RealTseTransactionServiceImpl
 * - The TSE Connector Server handles the mode difference internally
 * - This keeps the main server simple - it just forwards to TSE Server
 * - DISABLED mode uses DisabledTseTransactionServiceImpl for non-German markets
 *
 * @property tseTransactionRepository Repository for TSE operations
 * @property tseSignClient Client for TSE server communication
 * @since 1.0.0
 * @author Generated with Claude Code
 */
class TseServiceFactory(
    private val tseTransactionRepository: TseTransactionRepository,
    private val tseSignClient: TseSignProvider,
    private val signer: TseVorgangSigner,
) {
    /**
     * Gets appropriate TSE service based on configured mode.
     *
     * Both SIMULATION and NORMAL modes use the same service implementation
     * because the TSE Connector Server handles simulation internally.
     */
    suspend fun getTseService(): TseTransactionService {
        try {
            // Get TSE mode from cached configuration
            val tseMode = ShopConfigurationManager.getTseMode()

            return when (tseMode) {
                // Both SIMULATION and NORMAL use RealTseTransactionServiceImpl
                // The TSE Connector Server handles simulation mode internally
                TseMode.SIMULATION, TseMode.NORMAL -> RealTseTransactionServiceImpl(tseTransactionRepository, tseSignClient, signer)
                TseMode.DISABLED -> DisabledTseTransactionServiceImpl()
            }
        } catch (e: Exception) {
            // Fallback to disabled mode instead of throwing
            return DisabledTseTransactionServiceImpl()
        }
    }
}
