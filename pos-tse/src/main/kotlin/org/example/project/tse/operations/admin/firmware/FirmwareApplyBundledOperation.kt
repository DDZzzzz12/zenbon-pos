/**
 * Apply bundled firmware update to TSE hardware.
 *
 * SAFETY: This is a long-running operation. Power loss during firmware update
 * may brick the device. The client must warn the user before proceeding.
 *
 * Prerequisites:
 * - Admin login required
 * - Bundled firmware must be available (checked via FirmwareUpdateCheckOperation first)
 *
 * Endpoint: POST /admin/firmware/apply-bundled
 *
 * @param adminPin Admin PIN for TSE authentication
 * @since 2.1.0
 */
package org.example.project.tse.operations.admin.firmware

import com.secureflashcard.wormapi.WormInitializationState
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.BaseTseOperation

class FirmwareApplyBundledOperation(
    adminPin: String
) : BaseTseOperation<TseDto.FirmwareApplyResultDto>(adminPin) {

    /**
     * Apply bundled firmware update to TSE.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with firmware apply result or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.FirmwareApplyResultDto {
        logger.warn("Applying bundled firmware update - THIS IS A LONG-RUNNING OPERATION")

        val store = getWormStore(tseStatus)

        // TSE 2.0 added these preconditions. Unchecked, a violation reaches the operator as an
        // opaque SDK error — WORM_ERROR_TSE_HAS_UNFINISHED_TRANSACTIONS tells them nothing they
        // can act on, whereas "finish the 3 open transactions first" does.
        //
        // Checked before the Admin login on purpose: there is no sense spending one of the
        // three attempts standing between the device and a PIN lockout on an operation that
        // cannot proceed anyway.
        store.info().use { info ->
            if (info.initializationState() != WormInitializationState.WORM_INIT_INITIALIZED) {
                throw PosException(
                    code = PosErrorCode.TSE_INVALID_PARAMETERS,
                    httpStatus = HttpStatusCode.Conflict,
                    message = "The TSE must be initialized before a firmware update can be applied.",
                )
            }
            if (!info.hasValidTime()) {
                throw PosException(
                    code = PosErrorCode.TSE_INVALID_PARAMETERS,
                    httpStatus = HttpStatusCode.Conflict,
                    message = "Set the TSE time before applying a firmware update.",
                )
            }
            val now = System.currentTimeMillis() / 1000
            if (info.certificateExpirationDate() in 1 until now) {
                throw PosException(
                    code = PosErrorCode.TSE_INVALID_PARAMETERS,
                    httpStatus = HttpStatusCode.Conflict,
                    message = "The TSE certificate has expired; a firmware update can no longer " +
                        "be applied. The device must be replaced.",
                )
            }
            val open = info.startedTransactions()
            if (open > 0) {
                throw PosException(
                    code = PosErrorCode.TSE_INVALID_PARAMETERS,
                    httpStatus = HttpStatusCode.Conflict,
                    message = "Finish all open transactions before applying a firmware update " +
                        "($open still open).",
                )
            }
        }

        loginAsAdmin(store)

        // Read current firmware version
        val info = store.info()
        val previousVersion = info.use { it.softwareVersion().toString() }
        logger.info("Current firmware version: $previousVersion")

        // Check if bundled update is available
        val bundledAvailability = store.tse_firmwareUpdate_isBundledAvailable()
        val availabilityName = bundledAvailability.name

        if (availabilityName == "WORM_FW_UPDATE_NOT_AVAILABLE") {
            logger.warn("No bundled firmware update available")
            return TseDto.FirmwareApplyResultDto(
                success = false,
                message = "No bundled firmware update available",
                previousVersion = previousVersion
            )
        }

        // Apply the bundled firmware update
        logger.warn("Executing tse_firmwareUpdate_applyBundled()")
        store.tse_firmwareUpdate_applyBundled()

        // Try to read new version (may fail if TSE rebooted)
        val newVersion = try {
            val newInfo = store.info()
            newInfo.use { it.softwareVersion().toString() }
        } catch (e: Exception) {
            logger.warn("Could not read new firmware version (TSE may have rebooted): ${e.message}")
            null
        }

        logger.info("Bundled firmware update applied successfully. Previous: $previousVersion, New: $newVersion")

        return TseDto.FirmwareApplyResultDto(
            success = true,
            message = "Firmware update applied successfully." +
                    if (newVersion != null) " Updated from $previousVersion to $newVersion." else " TSE may need to be reconnected.",
            previousVersion = previousVersion,
            newVersion = newVersion
        )
    }
}
