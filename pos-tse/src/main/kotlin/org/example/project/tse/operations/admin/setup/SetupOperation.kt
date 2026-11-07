package org.example.project.tse.operations.admin.setup

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.models.TseStatus
import org.example.project.tse.tasks.TseMaintenanceTask

/**
 * Perform TSE setup for an uninitialized TSE.
 *
 * Delegates to TseInitializationManager.initialize() which provides:
 * - Safe credential state checking (prevents TSE bricking)
 * - Handling of partially initialized TSE
 * - Fresh TSE setup with proper sequence
 * - Self-test execution after setup
 * - Time synchronization
 * - Leaves TSE fully ready for transactions
 *
 * Required Parameters:
 * - adminPin: Admin PIN for TSE authentication
 * - adminPuk: Admin PUK for credential setup
 * - timeAdminPin: Time admin PIN for time operations
 * - mode: TSE operating mode (NORMAL or SIMULATION)
 * - clientId: Optional client ID to register
 *
 * Endpoint: POST /admin/setup
 *
 * @param adminPin Admin PIN for TSE authentication
 * @param adminPuk Admin PUK for credential setup
 * @param timeAdminPin Time admin PIN for time operations
 * @param mode TSE operating mode
 * @param clientId Optional client ID to register
 * @since 2.0.0
 */
class SetupOperation(
    adminPin: String,
    private val adminPuk: String,
    private val timeAdminPin: String,
    private val mode: TseMode,
    private val clientId: String? = null,
    private val credentialSeed: String,
    private val maintenanceTask: TseMaintenanceTask
) : BaseTseOperation<TseDto.AdminResultDto>(
    adminPin = adminPin,
    requiresConnection = false,  // Setup handles connection internally
    requiresLock = false          // TseInitializationManager handles its own locking
) {

    /**
     * Perform TSE setup via TseStatus.configureFromTseConfig.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with setup confirmation or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.AdminResultDto {
        logger.info("Performing TSE setup via TseInitializationManager.initialize()")

        val success = tseStatus.commissionFromOperator(
            clientIdValue = clientId,
            adminPin = adminPin,
            adminPuk = adminPuk,
            timeAdminPinValue = timeAdminPin,
            credentialSeed = credentialSeed,
            modeValue = mode,
        )

        return if (success) {
            logger.info("TSE initialization completed successfully")
            if (mode == TseMode.NORMAL) {
                maintenanceTask.start()
                TseDto.AdminResultDto(
                    success = true,
                    message = "TSE setup and initialization completed successfully. TSE is ready for transactions.",
                    clientId = tseStatus.getClientId()
                )
            } else {
                throw PosException(
                    code = PosErrorCode.TSE_SETUP_FAILED,
                    httpStatus = HttpStatusCode.InternalServerError,
                    message = "TSE setup failed",
                    cause = IllegalStateException("TSE initialization failed - mode need to be Normal: $mode "),
                )
            }
        } else {
            logger.error("TSE initialization failed")
            throw PosException(
                code = PosErrorCode.TSE_SETUP_FAILED,
                httpStatus = HttpStatusCode.InternalServerError,
                message = "TSE setup failed",
                cause = IllegalStateException("TSE initialization failed - check logs for details"),
            )
        }
    }
}
