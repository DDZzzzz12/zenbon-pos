package org.example.project.tse.operations.admin.firmware

import com.secureflashcard.wormapi.WormTseFirmwareUpdate
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.BaseTseOperation

/**
 * Check for available TSE firmware updates (bundled and online).
 *
 * Checks two sources for firmware updates:
 * 1. **Bundled updates**: Firmware included with the TSE SDK that can be applied offline
 *    via [com.secureflashcard.wormapi.WormStore.tse_firmwareUpdate_isBundledAvailable]
 * 2. **Online updates**: Firmware available from the manufacturer's update server
 *    via [com.secureflashcard.wormapi.WormStore.tse_firmwareUpdate_check]
 *
 * The online check may fail if the TSE SDK version does not support it, in which case
 * only the bundled update result is returned.
 *
 * Endpoint: POST /admin/firmware/check
 *
 * @param adminPin Admin PIN for TSE authentication
 * @since 2.0.0
 */
class FirmwareUpdateCheckOperation(
    adminPin: String
) : BaseTseOperation<TseDto.FirmwareUpdateCheckResultDto>(adminPin) {

    /**
     * Check for available firmware updates.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with FirmwareUpdateCheckResultDto or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.FirmwareUpdateCheckResultDto {
        logger.info("Checking for TSE firmware updates")

        val store = getWormStore(tseStatus)
        // No login: worm_tse_firmwareUpdate_check states no authorisation requirement.
        // Only firmwareUpdate_applyBundled needs Admin, and that operation keeps its login.

        // Check bundled (offline) firmware availability
        val bundledUpdate = store.tse_firmwareUpdate_isBundledAvailable()
        val bundledAvailable = bundledUpdate != WormTseFirmwareUpdate.WORM_FW_NONE
        val bundledVersion = if (bundledAvailable) bundledUpdate.name else null

        logger.info("Bundled firmware update: available=$bundledAvailable, version=$bundledVersion")

        // Try online firmware update check
        var onlineAvailable = false
        var downloadUrl: String? = null

        try {
            store.tse_firmwareUpdate_check().use { response ->
                onlineAvailable = response.isAvailable
                downloadUrl = response.downloadUrl().takeIf { it.isNotBlank() }
                logger.info("Online firmware update: available=$onlineAvailable, url=$downloadUrl")
            }
        } catch (e: Exception) {
            // Online check may not be supported by all SDK versions, or network/link errors
            logger.warn("Online firmware update check failed: ${e.message}")
        }

        val anyUpdateAvailable = bundledAvailable || onlineAvailable
        val message = buildString {
            if (!anyUpdateAvailable) {
                append("No firmware updates available")
            } else {
                if (bundledAvailable) append("Bundled update available: $bundledVersion")
                if (bundledAvailable && onlineAvailable) append("; ")
                if (onlineAvailable) append("Online update available")
            }
        }

        return TseDto.FirmwareUpdateCheckResultDto(
            updateAvailable = anyUpdateAvailable,
            bundledUpdateAvailable = bundledAvailable,
            bundledVersion = bundledVersion,
            downloadUrl = downloadUrl,
            message = message
        )
    }
}
