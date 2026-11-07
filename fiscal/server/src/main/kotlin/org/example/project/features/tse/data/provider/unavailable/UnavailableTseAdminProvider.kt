/**
 * Admin provider used when LOCAL mode is configured but the Swissbit TSE SDK is
 * absent from the runtime classpath.
 *
 * Every administrative operation throws [PosException] with
 * [PosErrorCode.TSE_UNREACHABLE] and a message naming the expected SDK path, so the
 * admin UI shows an actionable error instead of the server failing to boot.
 *
 * The read-only probes ([ping], [isInitialized]) answer "no" rather than throwing, so
 * status polling and health widgets degrade quietly instead of filling logs.
 *
 * @since 1.0.0
 */
package org.example.project.features.tse.data.provider.unavailable

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.infrastructure.errors.PosException
import org.slf4j.LoggerFactory

/**
 * Inert [TseAdminProvider] for a LOCAL configuration with no SDK installed.
 */
class UnavailableTseAdminProvider : TseAdminProvider {
    private val logger = LoggerFactory.getLogger(UnavailableTseAdminProvider::class.java)

    override suspend fun getStatus(): TseDto.StatusInfoDto = throw sdkMissing("getStatus")

    override suspend fun healthCheck(): TseDto.HealthCheckResultDto = throw sdkMissing("healthCheck")

    /** Probe, not a command: answer "not reachable" instead of throwing. */
    override suspend fun ping(): Boolean = false

    override suspend fun status(): TseDto.StatusInfoDto = throw sdkMissing("status")

    override suspend fun getInfo(): TseDto.TseInfoDto = throw sdkMissing("getInfo")

    override suspend fun flashHealth(): TseDto.FlashHealthResultDto = throw sdkMissing("flashHealth")

    override suspend fun setup(
        deviceId: String,
        credentials: TseDto.SetupCredentialsRequestDto,
    ): TseDto.AdminResultDto = throw sdkMissing("setup")

    override suspend fun factoryReset(): TseDto.AdminResultDto = throw sdkMissing("factoryReset")

    override suspend fun selfTest(clientId: String?): TseDto.SelfTestResultDto = throw sdkMissing("selfTest")

    override suspend fun forceSelfTest(clientId: String?): TseDto.SelfTestResultDto = throw sdkMissing("forceSelfTest")

    override suspend fun updateTime(): TseDto.TimeUpdateResultDto = throw sdkMissing("updateTime")

    override suspend fun registerClient(clientId: String): TseDto.ClientRegistrationResultDto = throw sdkMissing("registerClient")

    override suspend fun deregisterClient(clientId: String): TseDto.AdminResultDto = throw sdkMissing("deregisterClient")

    override suspend fun listClients(): TseDto.ClientListResultDto = throw sdkMissing("listClients")

    override suspend fun listTransactions(clientId: String?): TseDto.TransactionListResultDto = throw sdkMissing("listTransactions")

    override suspend fun lastTransaction(clientId: String?): TseDto.LastTransactionResultDto = throw sdkMissing("lastTransaction")

    override suspend fun exportLocal(outputPath: String): TseDto.LocalExportResultDto = throw sdkMissing("exportLocal")

    override suspend fun exportFull(outputPath: String): TseDto.ExportResultDto = throw sdkMissing("exportFull")

    override suspend fun exportIncremental(
        outputPath: String,
        lastState: String?,
        maxExportSize: Long,
    ): TseDto.ExportResultDto = throw sdkMissing("exportIncremental")

    override suspend fun exportTimeFiltered(
        outputPath: String,
        startDate: Long,
        endDate: Long,
        clientId: String?,
    ): TseDto.ExportResultDto = throw sdkMissing("exportTimeFiltered")

    override suspend fun exportTransactionFiltered(
        outputPath: String,
        startTransaction: Long,
        endTransaction: Long,
        clientId: String?,
    ): TseDto.ExportResultDto = throw sdkMissing("exportTransactionFiltered")

    override suspend fun getExportSize(lastState: String?): TseDto.ExportSizeDto = throw sdkMissing("getExportSize")

    override suspend fun deleteExportedData(outputPath: String): TseDto.ExportAndDeleteResultDto = throw sdkMissing("deleteExportedData")

    override suspend fun unblockUser(
        puk: String,
        userId: String?,
        newPin: String?,
    ): TseDto.AdminResultDto = throw sdkMissing("unblockUser")

    override suspend fun changePin(
        currentPin: String,
        newPin: String,
        userId: String?,
    ): TseDto.AdminResultDto = throw sdkMissing("changePin")

    override suspend fun enableCtss(): TseDto.AdminResultDto = throw sdkMissing("enableCtss")

    override suspend fun disableCtss(): TseDto.AdminResultDto = throw sdkMissing("disableCtss")

    override suspend fun checkFirmwareUpdate(): TseDto.FirmwareUpdateCheckResultDto = throw sdkMissing("checkFirmwareUpdate")

    override suspend fun applyBundledFirmwareUpdate(): TseDto.FirmwareApplyResultDto = throw sdkMissing("applyBundledFirmwareUpdate")

    override suspend fun decommission(): TseDto.AdminResultDto = throw sdkMissing("decommission")

    override suspend fun disableExportIfCspTestFails(): TseDto.AdminResultDto = throw sdkMissing("disableExportIfCspTestFails")

    override suspend fun browseEntries(request: TseDto.LogEntryBrowseRequestDto): TseDto.LogEntryBrowseResultDto = throw sdkMissing("browseEntries")

    override suspend fun exportLcm(): TseDto.LcmExportResultDto = throw sdkMissing("exportLcm")

    override suspend fun restartServer(): TseDto.AdminResultDto = throw sdkMissing("restartServer")

    override suspend fun logoutAdmin(): TseDto.AdminResultDto = throw sdkMissing("logoutAdmin")

    override suspend fun refreshConfig() {
        logger.warn("TSE SDK missing — refreshConfig is a no-op")
    }

    override suspend fun setMode(
        mode: String,
        clientId: String,
    ): Boolean = false

    /** Probe, not a command: an absent SDK means no initialized device. */
    override suspend fun isInitialized(): Boolean = false

    private fun sdkMissing(operation: String): PosException {
        logger.error("TSE admin operation '$operation' refused: Swissbit SDK not installed (expected at native/WormAPI.jar)")
        return PosException(
            PosErrorCode.TSE_UNREACHABLE,
            HttpStatusCode.FailedDependency,
            "TSE SDK is not installed. Place the Swissbit WormAPI.jar at native/WormAPI.jar " +
                "next to pos-server.jar and restart the server.",
        )
    }
}
