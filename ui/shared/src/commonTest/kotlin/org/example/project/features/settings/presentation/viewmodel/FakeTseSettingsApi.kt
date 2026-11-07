package org.example.project.features.settings.presentation.viewmodel

import org.example.project.core.data.remote.TseSettingsApiClient
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.dto.tse.TseVerificationDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException

/** Records the config saves and setup calls [TseSettingsViewModel] makes; everything else is unused. */
class FakeTseSettingsApi : TseSettingsApiClient {
    val updateRequests = mutableListOf<TseConfigDto.UpdateTseConfigRequest>()
    val setupCalls = mutableListOf<TseDto.SetupCredentialsRequestDto>()

    /** When set, [updateConfig] refuses with it, as the server does. */
    var updateConfigError: PosApiException? = null

    /** When set, [setup] refuses with it, as the server does. */
    var setupError: PosApiException? = null

    /** What the save reports as applied; [applyOutcome] null mimics a server that predates the field. */
    var applyStatus = TseConfigDto.TseModeApplyStatus.DISABLED
    var applyOutcome: TseConfigDto.TseModeApplyStatus? = null

    /** The server's persisted config: a fresh install, DISABLED. */
    var storedConfig = TseConfigDto.TseConfig(timeAdminPin = "")

    override suspend fun getConfig() = storedConfig

    override suspend fun updateConfig(request: TseConfigDto.UpdateTseConfigRequest): TseConfigDto.TseConfigUpdateResultDto {
        updateRequests += request
        updateConfigError?.let { throw it }
        storedConfig = storedConfig.copy(mode = request.mode ?: storedConfig.mode)
        return TseConfigDto.TseConfigUpdateResultDto(
            config = storedConfig,
            applyStatus = applyStatus,
            message = "saved",
            applyOutcome = applyOutcome,
        )
    }

    /** As the server does, a successful setup saves the mode the TSE was commissioned in: NORMAL. */
    override suspend fun setup(credentials: TseDto.SetupCredentialsRequestDto): TseDto.AdminResultDto {
        setupCalls += credentials
        setupError?.let { throw it }
        storedConfig = storedConfig.copy(mode = TseMode.NORMAL)
        return TseDto.AdminResultDto(success = true, message = "commissioned")
    }

    /** Whether the TSE answers; false by default — a DISABLED install never opens the device. */
    var available = false
    var pingCount = 0

    override suspend fun ping(): Map<String, Boolean> {
        pingCount++
        return mapOf("available" to available)
    }

    override suspend fun adminSessionStatus() = TseDto.TseAdminSessionStatusDto(false, 300L)

    // The refresh after a successful setup reads these; a device that does not answer is enough.
    override suspend fun getStatus(): TseDto.StatusInfoDto = throw unreachable()

    override suspend fun getInfo(): TseDto.TseInfoDto = throw unreachable()

    private fun unreachable() = PosApiException(PosErrorCode.TSE_UNREACHABLE, 503, "offline", null)

    override suspend fun getHealth(): TseDto.HealthCheckResultDto = TODO()

    override suspend fun getFlashHealth(): TseDto.FlashHealthResultDto = TODO()

    override suspend fun runSelfTest(): TseDto.SelfTestResultDto = TODO()

    override suspend fun updateTime(): TseDto.TimeUpdateResultDto = TODO()

    override suspend fun unlockAdmin(pin: String): TseDto.TseAdminSessionStatusDto = TODO()

    override suspend fun lockAdmin(): TseDto.TseAdminSessionStatusDto = TODO()

    override suspend fun restartTse(): TseDto.AdminResultDto = TODO()

    override suspend fun factoryReset(): TseDto.AdminResultDto = TODO()

    override suspend fun exportFull(): TseDto.ExportResultDto = TODO()

    override suspend fun exportIncremental(request: TseDto.IncrementalExportRequestDto): TseDto.ExportResultDto = TODO()

    override suspend fun exportTimeFiltered(request: TseDto.TimeFilteredExportRequestDto): TseDto.ExportResultDto = TODO()

    override suspend fun getExportSize(): TseDto.ExportSizeDto = TODO()

    override suspend fun deleteExportedData(): TseDto.ExportAndDeleteResultDto = TODO()

    override suspend fun registerExportArchive(fileName: String): TseDto.ExportArchiveDto = TODO()

    override suspend fun checkFirmwareUpdate(): TseDto.FirmwareUpdateCheckResultDto = TODO()

    override suspend fun applyBundledFirmwareUpdate(): TseDto.FirmwareApplyResultDto = TODO()

    override suspend fun decommission(): TseDto.AdminResultDto = TODO()

    override suspend fun disableExportIfCspTestFails(): TseDto.AdminResultDto = TODO()

    override suspend fun browseEntries(request: TseDto.LogEntryBrowseRequestDto): TseDto.LogEntryBrowseResultDto = TODO()

    override suspend fun exportLcm(): TseDto.LcmExportResultDto = TODO()

    override suspend fun getClients(): TseDto.ClientListResultDto = TODO()

    override suspend fun getTransactions(): TseDto.TransactionListResultDto = TODO()

    override suspend fun getLastTransaction(): TseDto.LastTransactionResultDto = TODO()

    override suspend fun verifyExport(fileName: String): TseVerificationDto.ReportDto = TODO()
}
