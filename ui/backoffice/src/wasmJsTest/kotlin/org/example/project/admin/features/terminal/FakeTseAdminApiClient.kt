package org.example.project.admin.features.terminal

import org.example.project.core.data.remote.TseSettingsApiClient
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto

class FakeTseAdminApiClient : TseSettingsApiClient {
    var unlockAdminResult: Result<TseDto.TseAdminSessionStatusDto> =
        Result.success(
            TseDto.TseAdminSessionStatusDto(unlocked = true, idleTimeoutSeconds = 600),
        )
    var unlockAdminCallCount = 0
    var lastUnlockPin: String? = null

    override suspend fun unlockAdmin(pin: String): TseDto.TseAdminSessionStatusDto {
        unlockAdminCallCount++
        lastUnlockPin = pin
        return unlockAdminResult.getOrThrow()
    }

    override suspend fun getConfig() = throw NotImplementedError()

    override suspend fun updateConfig(request: TseConfigDto.UpdateTseConfigRequest) = throw NotImplementedError()

    override suspend fun getStatus() = throw NotImplementedError()

    override suspend fun getInfo() = throw NotImplementedError()

    override suspend fun getHealth() = throw NotImplementedError()

    override suspend fun getFlashHealth() = throw NotImplementedError()

    override suspend fun ping() = throw NotImplementedError()

    override suspend fun runSelfTest() = throw NotImplementedError()

    override suspend fun updateTime() = throw NotImplementedError()

    override suspend fun lockAdmin() = throw NotImplementedError()

    override suspend fun adminSessionStatus() = throw NotImplementedError()

    override suspend fun setup(credentials: TseDto.SetupCredentialsRequestDto) = throw NotImplementedError()

    override suspend fun restartTse() = throw NotImplementedError()

    override suspend fun factoryReset() = throw NotImplementedError()

    override suspend fun exportFull() = throw NotImplementedError()

    override suspend fun exportIncremental(request: TseDto.IncrementalExportRequestDto) = throw NotImplementedError()

    override suspend fun exportTimeFiltered(request: TseDto.TimeFilteredExportRequestDto) = throw NotImplementedError()

    override suspend fun getExportSize() = throw NotImplementedError()

    override suspend fun deleteExportedData() = throw NotImplementedError()

    override suspend fun registerExportArchive(fileName: String) = throw NotImplementedError()

    override suspend fun checkFirmwareUpdate() = throw NotImplementedError()

    override suspend fun applyBundledFirmwareUpdate() = throw NotImplementedError()

    override suspend fun decommission() = throw NotImplementedError()

    override suspend fun disableExportIfCspTestFails() = throw NotImplementedError()

    override suspend fun browseEntries(request: TseDto.LogEntryBrowseRequestDto) = throw NotImplementedError()

    override suspend fun exportLcm() = throw NotImplementedError()

    override suspend fun getClients() = throw NotImplementedError()

    override suspend fun getTransactions() = throw NotImplementedError()

    override suspend fun getLastTransaction() = throw NotImplementedError()

    override suspend fun verifyExport(fileName: String) = throw NotImplementedError()
}
