/**
 * TSE decommission operation.
 *
 * CRITICAL SAFETY: This is an IRREVERSIBLE operation. Once decommissioned,
 * the TSE can only be restored via factory reset (development firmware only).
 *
 * Prerequisites (checked server-side):
 * - Admin login required
 * - TSE must be in INITIALIZED state
 * - No unfinished transactions (will fail otherwise)
 *
 * Endpoint: POST /admin/decommission
 *
 * @param adminPin Admin PIN for TSE authentication
 * @since 2.1.0
 */
package org.example.project.tse.operations.admin.setup

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.BaseTseOperation

class DecommissionOperation(
    adminPin: String
) : BaseTseOperation<TseDto.AdminResultDto>(adminPin) {

    /**
     * Perform TSE decommission.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with decommission confirmation or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.AdminResultDto {
        logger.warn("Performing TSE DECOMMISSION - THIS IS AN IRREVERSIBLE OPERATION")

        val store = getWormStore(tseStatus)
        loginAsAdmin(store)

        // Pre-flight checks
        // Read everything inside ONE use{} block: close() frees the native handle, and reading
        // it afterwards is a use-after-free that SIGSEGVs libWormAPIJni on a real TSE.
        val (initState, startedTransactions) = store.info().use { info ->
            info.initializationState().name to info.startedTransactions()
        }

        if (initState != "WORM_INIT_INITIALIZED") {
            logger.error("TSE is not in INITIALIZED state (current: $initState)")
            return TseDto.AdminResultDto(
                success = false,
                message = "TSE must be in INITIALIZED state to decommission (current: $initState)"
            )
        }

        if (startedTransactions > 0) {
            logger.error("TSE has $startedTransactions unfinished transactions")
            return TseDto.AdminResultDto(
                success = false,
                message = "TSE has $startedTransactions unfinished transaction(s). All transactions must be completed before decommission."
            )
        }

        // Execute decommission
        logger.warn("Executing tse_decommission()")
        store.tse_decommission()

        logger.info("TSE decommission completed successfully")

        return TseDto.AdminResultDto(
            success = true,
            message = "TSE decommissioned successfully. The TSE is now in DECOMMISSIONED state."
        )
    }
}
