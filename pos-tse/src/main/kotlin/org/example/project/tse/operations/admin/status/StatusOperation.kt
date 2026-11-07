package org.example.project.tse.operations.admin.status

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.models.TseStatus

/**
 * Get TSE status information.
 *
 * This operation returns the current TSE status including:
 * - Connection state
 * - Operating mode (NORMAL/SIMULATION)
 * - Mount point
 * - Busy state
 * - Initialization state
 * - Last error (if any)
 *
 * Special Characteristics:
 * - Does NOT require TSE hardware connection
 * - Does NOT require exclusive lock
 * - Always available regardless of TSE state
 *
 * Endpoint: GET /admin/status
 *
 * @since 2.0.0
 */
class StatusOperation : BaseTseOperation<TseDto.StatusInfoDto>(
    adminPin = "",
    requiresConnection = false,
    requiresLock = false
) {

    /**
     * Get the current TSE status information.
     *
     * @param tseStatus Current TSE status manager
     * @return Result.Success with StatusInfoDto containing current status
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.StatusInfoDto {
        logger.debug("Getting TSE status information")
        return tseStatus.getStatusInfo()
    }
}
