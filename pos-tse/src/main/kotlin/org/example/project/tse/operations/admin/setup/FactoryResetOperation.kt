package org.example.project.tse.operations.admin.setup

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.models.TseStatus

/**
 * Perform TSE factory reset (development firmware only).
 *
 * WARNING: This is a DESTRUCTIVE operation that resets the TSE to factory defaults.
 * This method only works on development TSE devices.
 *
 * From WormStore.java (line 514-520):
 * "Note: This method only works on development TSEs and will be removed from the
 * production firmware."
 *
 * Effects:
 * - All credentials are reset to factory defaults
 * - All stored data is erased
 * - TSE returns to UNINITIALIZED state
 * - Cannot be reversed
 *
 * Restrictions:
 * - Only available on development firmware
 * - Production TSEs do not support this operation
 *
 * Endpoint: POST /admin/factory-reset
 *
 * @param adminPin Admin PIN for TSE authentication
 * @since 2.0.0
 */
class FactoryResetOperation(
    adminPin: String
) : BaseTseOperation<TseDto.AdminResultDto>(adminPin) {

    /**
     * Perform TSE factory reset.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with reset confirmation or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.AdminResultDto {
        logger.warn("Performing TSE factory reset - THIS IS A DESTRUCTIVE OPERATION")

        val store = getWormStore(tseStatus)

        // Check if this is a development TSE
        val info = store.info()
        val isDevelopment = info.use { it.isDevelopmentFirmware() }

        if (!isDevelopment) {
            logger.error("Factory reset is only available on development TSE devices")
            return TseDto.AdminResultDto(
                success = false,
                message = "Factory reset is only available on development TSE devices"
            )
        }

        // Perform factory reset
        logger.warn("Executing tse_factoryReset()")
        store.tse_factoryReset()

        logger.info("TSE factory reset completed successfully")

        return TseDto.AdminResultDto(
            success = true,
            message = "TSE factory reset completed successfully. TSE is now uninitialized."
        )
    }
}
