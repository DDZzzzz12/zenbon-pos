package org.example.project.tse.operations.admin.status

import com.secureflashcard.wormapi.WormInitializationState
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.models.TseStatus

/**
 * Perform health check on TSE hardware.
 *
 * Checks the following health indicators:
 * - Connection status
 * - Initialization state (must be INITIALIZED)
 * - Self-test status (must have passed)
 * - Time validity (must have valid time)
 * - Remaining signatures (must be > 1000)
 * - Available capacity
 *
 * Overall Health:
 * TSE is considered healthy if ALL of the following are true:
 * - Initialized
 * - Valid time
 * - Self-test passed
 * - Remaining signatures > 1000
 *
 * Endpoint: POST /admin/health
 *
 * Takes no credential: every field it reads comes from tse_getInfo, which needs no login.
 *
 * @since 2.0.0
 */
class HealthCheckOperation : BaseTseOperation<TseDto.HealthCheckResultDto>(adminPin = "") {

    /**
     * Perform TSE health check.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with HealthCheckResultDto or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.HealthCheckResultDto {
        logger.info("Performing TSE health check")

        val store = getWormStore(tseStatus)
        val info = store.info()

        return info.use { i ->
            val isInitialized = i.initializationState() == WormInitializationState.WORM_INIT_INITIALIZED
            val hasValidTime = i.hasValidTime()
            val hasPassedSelfTest = i.hasPassedSelfTest()
            val remainingSignatures = i.remainingSignatures()
            val availableCapacity = i.capacity() - i.size()

            val healthy = isInitialized &&
                    hasValidTime &&
                    hasPassedSelfTest &&
                    remainingSignatures > 1000

            logger.info(
                "Health check complete - healthy: $healthy, " +
                        "initialized: $isInitialized, validTime: $hasValidTime, " +
                        "selfTest: $hasPassedSelfTest, signatures: $remainingSignatures"
            )

            TseDto.HealthCheckResultDto(
                healthy = healthy,
                initialized = isInitialized,
                validTime = hasValidTime,
                passedSelfTest = hasPassedSelfTest,
                remainingSignatures = remainingSignatures,
                availableCapacity = availableCapacity
            )
        }
    }
}
