package org.example.project.tse.operations.admin.status

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.models.TseStatus
import java.util.*

/**
 * Get comprehensive TSE information.
 *
 * Retrieves detailed information from the TSE hardware including:
 * - Initialization state
 * - Serial number (bytes and hex)
 * - Public key
 * - Certificate expiration
 * - Firmware version (hardware/software)
 * - Capacity and size
 * - Signature statistics (created/max/remaining)
 * - Transaction statistics
 * - Client registration info
 * - Self-test and time sync status
 * - Flash health indicators
 *
 * WormAPI Method: info() - line 495
 *
 * Endpoint: POST /admin/info
 *
 * @param adminPin Admin PIN for TSE authentication
 * @since 2.0.0
 */
class GetInfoOperation(
    adminPin: String
) : BaseTseOperation<TseDto.TseInfoDto>(adminPin) {

    /**
     * Retrieve comprehensive TSE information.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with TseInfoDto or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.TseInfoDto {
        logger.info("Retrieving TSE information")
        val store = getWormStore(tseStatus)
        val info = store.info()

        info.use { i ->
            val tseInfoDto = TseDto.TseInfoDto(
                initializationState = i.initializationState().toString(),
                hasValidTime = i.hasValidTime(),
                hasPassedSelfTest = i.hasPassedSelfTest(),
                isDevelopmentFirmware = i.isDevelopmentFirmware,
                // No .toInt(): every real TSE exceeds Int.MAX_VALUE in bytes.
                capacity = i.capacity(),
                size = i.size(),
                hasChangedPuk = i.hasChangedPuk(),
                hasChangedAdminPin = i.hasChangedAdminPin(),
                hasChangedTimeAdminPin = i.hasChangedTimeAdminPin(),
                timeUntilNextSelfTest = i.timeUntilNextSelfTest().toInt(),
                startedTransactions = i.startedTransactions().toInt(),
                maxStartedTransactions = i.maxStartedTransactions().toInt(),
                createdSignatures = i.createdSignatures().toInt(),
                maxSignatures = i.maxSignatures().toInt(),
                remainingSignatures = i.remainingSignatures().toInt(),
                maxTimeSynchronizationDelay = i.maxTimeSynchronizationDelay().toInt(),
                maxUpdateDelay = i.maxUpdateDelay().toInt(),
                tsePublicKey = Base64.getEncoder().encodeToString(i.tsePublicKey()),
                timeUntilNextTimeSynchronization = i.timeUntilNextTimeSynchronization().toInt(),
                tseSerialNumberBytes = Base64.getEncoder().encodeToString(i.tseSerialNumber()),
                tseSerialNumberHex = i.tseSerialNumber().joinToString("") { "%02x".format(it) },
                tseDescription = i.tseDescription(),
                registeredClients = i.registeredClients().toInt(),
                maxRegisteredClients = i.maxRegisteredClients().toInt(),
                certificateExpirationDate = i.certificateExpirationDate(),
                tarExportSizeInSectors = i.tarExportSizeInSectors().toInt(),
                tarExportSize = i.tarExportSize().toInt(),
                hardwareVersion = i.hardwareVersion().toInt(),
                softwareVersion = i.softwareVersion().toInt(),
                formFactor = i.formFactor().toString(),
                isTseV2 = i.isTSEv2(),
                loggedInUser = i.loggedInUser().toString(),
                loggedInUserAutoLogOutTimeout = i.loggedInUserAutoLogOutTimeout(),
                hasChangedAdminPuk = i.hasChangedAdminPuk(),
                hasChangedTimeAdminPuk = i.hasChangedTimeAdminPuk(),
                hasChangedLoggerPuk = i.hasChangedLoggerPuk(),
                hasChangedLoggerPin = i.hasChangedLoggerPin(),
            )

            logger.info("TSE information retrieved successfully")
            return tseInfoDto
        }
    }
}
