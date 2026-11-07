package org.example.project.tse.operations.admin.time

import com.secureflashcard.wormapi.WormUserId
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.models.TseStatus

/**
 * Update TSE time to current system time.
 *
 * The TSE requires periodic time synchronization to maintain accurate timestamps
 * on signatures. This operation sets the TSE's internal clock to match the
 * current system time.
 *
 * Time Synchronization Requirements:
 * - TSE time must be synchronized periodically (maxTimeSynchronizationDelay)
 * - Time is set in Unix epoch seconds
 * - Required for valid signature timestamps
 * - Part of German fiscal compliance (KassenSichV)
 *
 * WormAPI Method: tse_updateTime() - line 663
 *
 * Endpoint: POST /admin/time
 *
 * @param adminPin Admin PIN for TSE authentication
 * @since 2.0.0
 */
class UpdateTimeOperation(
    adminPin: String
) : BaseTseOperation<TseDto.TimeUpdateResultDto>(adminPin) {

    /**
     * Update TSE time to current system time.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with time update confirmation or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.TimeUpdateResultDto {
        logger.info("Updating TSE time")

        val store = getWormStore(tseStatus)

        // TimeAdmin, not Admin: tse_updateTime accepts either, and the TimeAdmin PIN is the
        // one credential the Swissbit guidance manual (2.5) permits the ERS to store. The
        // scheduled time sync in TseMaintenanceTask already uses TimeAdmin.
        loginAsTimeAdmin(store, tseStatus.getTimeAdminPin())

        val currentTime = System.currentTimeMillis() / 1000
        try {
            // Update time (Unix timestamp in seconds)
            store.tse_updateTime(currentTime)
            logger.info("TSE time updated successfully to: $currentTime")
        } finally {
            // Mandatory on TSE 2.0: a resting TimeAdmin may neither export nor read entries,
            // so leaving it logged in would break every later backup.
            runCatching { store.user_logout(WormUserId.WORM_USER_TIME_ADMIN) }
        }

        return TseDto.TimeUpdateResultDto(
            success = true,
            tseTimestamp = currentTime,
            message = "TSE time updated successfully"
        )
    }
}
