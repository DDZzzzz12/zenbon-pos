package org.example.project.tse.operations.admin.ctss

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.models.TseStatus

/**
 * Disable the CTSS interface.
 *
 * Recommended when sending TSE for maintenance to prevent
 * reading recorded transactions.
 * This setting persists across power cycles.
 *
 * Use Cases:
 * - Sending TSE for service/maintenance
 * - Security lockdown
 * - Preventing unauthorized access during transport
 *
 * Effects:
 * - Transactions cannot be signed
 * - Data export is blocked
 * - TSE enters secure lockdown state
 *
 * Persistence:
 * - Setting persists across TSE power cycles
 * - Must be re-enabled to resume operations
 *
 * WormAPI Method: tse_ctss_disable() - line 596
 *
 * Endpoint: POST /admin/ctss/disable
 *
 * @param adminPin Admin PIN for TSE authentication
 * @since 2.0.0
 */
class CtssDisableOperation(
    adminPin: String
) : BaseTseOperation<TseDto.AdminResultDto>(adminPin) {

    /**
     * Disable CTSS interface.
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

        logger.info("Disabling CTSS interface")

        val store = getWormStore(tseStatus)

        // Login as admin
        loginAsAdmin(store)

        store.tse_ctss_disable()

        logger.info("CTSS interface disabled successfully")

        return TseDto.AdminResultDto(
            success = true,
            message = "CTSS interface disabled successfully"
        )
    }
}
