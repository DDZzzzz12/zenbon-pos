package org.example.project.admin.features.dashboard

import org.example.project.core.data.remote.TseSettingsApiClient
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.dto.tse.TseVerificationDto

class FakeTseSettingsApiClient(
    private val health: () -> TseDto.HealthCheckResultDto,
) : TseSettingsApiClient {
    override suspend fun getHealth(): TseDto.HealthCheckResultDto = health()

    override suspend fun getConfig(): TseConfigDto.TseConfig = throw NotImplementedError()

    override suspend fun updateConfig(request: TseConfigDto.UpdateTseConfigRequest): TseConfigDto.TseConfigUpdateResultDto = throw NotImplementedError()

    override suspend fun getStatus(): TseDto.StatusInfoDto = throw NotImplementedError()

    override suspend fun getInfo(): TseDto.TseInfoDto = throw NotImplementedError()

    override suspend fun getFlashHealth(): TseDto.FlashHealthResultDto = throw NotImplementedError()

    override suspend fun ping(): Map<String, Boolean> = throw NotImplementedError()

    override suspend fun runSelfTest(): TseDto.SelfTestResultDto = throw NotImplementedError()

    override suspend fun updateTime(): TseDto.TimeUpdateResultDto = throw NotImplementedError()

    override suspend fun unlockAdmin(pin: String): TseDto.TseAdminSessionStatusDto = throw NotImplementedError()

    override suspend fun lockAdmin(): TseDto.TseAdminSessionStatusDto = throw NotImplementedError()

    override suspend fun adminSessionStatus(): TseDto.TseAdminSessionStatusDto = throw NotImplementedError()

    override suspend fun setup(credentials: TseDto.SetupCredentialsRequestDto): TseDto.AdminResultDto = throw NotImplementedError()

    override suspend fun restartTse(): TseDto.AdminResultDto = throw NotImplementedError()

    override suspend fun factoryReset(): TseDto.AdminResultDto = throw NotImplementedError()

    override suspend fun exportFull(): TseDto.ExportResultDto = throw NotImplementedError()

    override suspend fun exportIncremental(request: TseDto.IncrementalExportRequestDto): TseDto.ExportResultDto = throw NotImplementedError()

    override suspend fun exportTimeFiltered(request: TseDto.TimeFilteredExportRequestDto): TseDto.ExportResultDto = throw NotImplementedError()

    override suspend fun getExportSize(): TseDto.ExportSizeDto = throw NotImplementedError()

    override suspend fun deleteExportedData(): TseDto.ExportAndDeleteResultDto = throw NotImplementedError()

    override suspend fun registerExportArchive(fileName: String): TseDto.ExportArchiveDto = throw NotImplementedError()

    override suspend fun checkFirmwareUpdate(): TseDto.FirmwareUpdateCheckResultDto = throw NotImplementedError()

    override suspend fun applyBundledFirmwareUpdate(): TseDto.FirmwareApplyResultDto = throw NotImplementedError()

    override suspend fun decommission(): TseDto.AdminResultDto = throw NotImplementedError()

    override suspend fun disableExportIfCspTestFails(): TseDto.AdminResultDto = throw NotImplementedError()

    override suspend fun browseEntries(request: TseDto.LogEntryBrowseRequestDto): TseDto.LogEntryBrowseResultDto = throw NotImplementedError()

    override suspend fun exportLcm(): TseDto.LcmExportResultDto = throw NotImplementedError()

    override suspend fun getClients(): TseDto.ClientListResultDto = throw NotImplementedError()

    override suspend fun getTransactions(): TseDto.TransactionListResultDto = throw NotImplementedError()

    override suspend fun getLastTransaction(): TseDto.LastTransactionResultDto = throw NotImplementedError()

    override suspend fun verifyExport(fileName: String): TseVerificationDto.ReportDto = throw NotImplementedError()
}
