package org.example.project.tse.operations.admin.status

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.models.TseStatus

/**
 * Get TSE flash memory health summary.
 *
 * Provides information about the health of the TSE's flash memory:
 * - ECC errors (uncorrectable errors are critical)
 * - Remaining spare blocks percentage
 * - Remaining erase counts percentage
 * - Data retention estimates (10-year retention)
 * - Replacement recommendation
 *
 * Health Indicators:
 * - uncorrectableEccErrors: Number of errors that couldn't be corrected (critical if > 0)
 * - percentageRemainingSpareBlocks: Remaining backup blocks (0-100%)
 * - percentageRemainingEraseCounts: Remaining write cycles (0-100%)
 * - percentageRemainingTenYearsDataRetention: Data retention estimate (0-100%)
 * - needsReplacement: True if TSE should be replaced
 *
 * WormAPI Method: flashHealthSummary() - line 505
 *
 * Endpoint: POST /admin/flash-health
 *
 * @param adminPin Admin PIN for TSE authentication
 * @since 2.0.0
 */
class FlashHealthOperation(
    adminPin: String
) : BaseTseOperation<TseDto.FlashHealthResultDto>(adminPin) {

    /**
     * Get flash memory health summary.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with FlashHealthResultDto or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.FlashHealthResultDto {
        logger.info("Getting flash health summary")

        val store = getWormStore(tseStatus)

        return store.flashHealthSummary().use { health ->
            val needsReplacement = health.needsReplacement()

            if (needsReplacement) {
                logger.warn("TSE NEEDS REPLACEMENT!")
            } else {
                logger.info("Flash health: OK (spare blocks: ${health.percentageRemainingSpareBlocks()}%)")
            }

            TseDto.FlashHealthResultDto(
                success = true,
                uncorrectableEccErrors = health.uncorrectableEccErrors(),
                percentageRemainingSpareBlocks = health.percentageRemainingSpareBlocks().toInt(),
                percentageRemainingEraseCounts = health.percentageRemainingEraseCounts().toInt(),
                percentageRemainingTenYearsDataRetention = health.percentageRemainingTenYearsDataRetention().toInt(),
                needsReplacement = needsReplacement,
                message = if (needsReplacement) {
                    "WARNING: TSE needs replacement!"
                } else {
                    "TSE flash health is OK"
                }
            )
        }
    }
}
