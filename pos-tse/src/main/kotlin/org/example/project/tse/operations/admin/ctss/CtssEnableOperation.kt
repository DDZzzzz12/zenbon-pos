package org.example.project.tse.operations.admin.ctss

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.models.TseStatus

/**
 * Enable the CTSS (Cryptographic Transaction Storage Service) interface.
 *
 * CTSS must be enabled for transactions and data export.
 * This setting persists across power cycles.
 *
 * CTSS Purpose:
 * - Required for signing transactions
 * - Required for data export
 * - Security feature to prevent unauthorized access
 *
 * Persistence:
 * - Setting persists across TSE power cycles
 * - Must be enabled after factory reset
 *
 * WormAPI Method: tse_ctss_enable() - line 582
 *
 * Endpoint: POST /admin/ctss/enable
 *
 * @param adminPin Admin PIN for TSE authentication
 * @since 2.0.0
 */
class CtssEnableOperation(
    adminPin: String
) : BaseTseOperation<TseDto.AdminResultDto>(adminPin) {

    /**
     * Enable CTSS interface.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with confirmation or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.AdminResultDto {
        if (tseStatus.isTseV2()) {
            // TSE 2.0 removed this feature and the SDK call became a silent no-op, so a
            // success response would tell the operator something was applied when nothing
            // was. Report it honestly instead.
            logger.info("Not applicable on TSE 2.0 — CTSS interface was removed in that generation")
            return TseDto.AdminResultDto(
                success = false,
                message = "Not applicable on TSE 2.0: the CTSS interface was removed in that generation.",
                clientId = tseStatus.getClientId(),
            )
        }

        logger.info("Enabling CTSS interface")

        val store = getWormStore(tseStatus)

        // Login as admin
        loginAsAdmin(store)

        store.tse_ctss_enable()

        logger.info("CTSS interface enabled successfully")

        return TseDto.AdminResultDto(
            success = true,
            message = "CTSS interface enabled successfully"
        )
    }
}
