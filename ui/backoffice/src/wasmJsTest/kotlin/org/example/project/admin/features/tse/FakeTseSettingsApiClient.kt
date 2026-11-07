package org.example.project.admin.features.tse

import kotlinx.coroutines.CompletableDeferred
import org.example.project.core.data.remote.TseSettingsApiClient
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.dto.tse.TseVerificationDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseExportArchiveOrigin
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException

class FakeTseSettingsApiClient : TseSettingsApiClient {
    // --- RB4-own knobs (TseAdminGateTest) ---
    var unlocked = false
    var idleTimeoutSeconds = 300L
    var unlockPins = mutableListOf<String>()
    var setupCalls = mutableListOf<TseDto.SetupCredentialsRequestDto>()
    var setupResult = TseDto.AdminResultDto(success = true, message = "commissioned")
    var lockCount = 0

    // --- RB3 Part-B contract (throws-hook + captured value + counter) ---
    /** When non-null, [unlockAdmin] returns this instead of computing `pin == "1234"`. */
    var unlockResult: TseDto.TseAdminSessionStatusDto? = null
    var lastUnlockPin: String? = null
    var setupError: (() -> Nothing)? = null
    var lastSetupCredentials: TseDto.SetupCredentialsRequestDto? = null
    var setupCallCount = 0

    // --- first-terminal TSE-mode probe ---
    var configResult: TseConfigDto.TseConfig = TseConfigDto.TseConfig(mode = TseMode.NORMAL, timeAdminPin = "")
    var configError: (() -> Nothing)? = null
    var getConfigCallCount = 0

    /** When set, [getConfig] suspends until this completes — lets a test hold a probe "in flight". */
    var configGate: CompletableDeferred<Unit>? = null

    override suspend fun getConfig(): TseConfigDto.TseConfig {
        getConfigCallCount++
        configGate?.await()
        configError?.let { it() }
        return configResult
    }

    override suspend fun unlockAdmin(pin: String): TseDto.TseAdminSessionStatusDto {
        unlockPins += pin
        lastUnlockPin = pin
        val result =
            unlockResult
                ?: TseDto.TseAdminSessionStatusDto(pin == "1234", idleTimeoutSeconds) // correct PIN opens the sitting
        unlocked = result.unlocked
        return result
    }

    override suspend fun lockAdmin(): TseDto.TseAdminSessionStatusDto {
        lockCount++
        unlocked = false
        return TseDto.TseAdminSessionStatusDto(false, idleTimeoutSeconds)
    }

    override suspend fun adminSessionStatus() = TseDto.TseAdminSessionStatusDto(unlocked, idleTimeoutSeconds)

    override suspend fun setup(credentials: TseDto.SetupCredentialsRequestDto): TseDto.AdminResultDto {
        setupCallCount++
        setupCalls += credentials
        lastSetupCredentials = credentials
        setupError?.let { it() }
        return setupResult
    }

    // helper for the retry tests: a call that fails while the sitting is locked
    suspend fun signingOp(): String {
        if (!unlocked) {
            throw PosApiException(
                code = PosErrorCode.TSE_ADMIN_LOCKED,
                httpStatus = 423,
                message = "locked",
                traceId = null,
            )
        }
        return "ok"
    }

    // --- first-terminal commissioning saves the TimeAdmin PIN before setup ---
    var updateConfigRequests = mutableListOf<TseConfigDto.UpdateTseConfigRequest>()
    var updateConfigError: (() -> Nothing)? = null

    override suspend fun updateConfig(request: TseConfigDto.UpdateTseConfigRequest): TseConfigDto.TseConfigUpdateResultDto {
        updateConfigRequests += request
        updateConfigError?.let { it() }
        return TseConfigDto.TseConfigUpdateResultDto(
            config = configResult.copy(timeAdminPin = request.timeAdminPin ?: configResult.timeAdminPin),
            applyStatus = TseConfigDto.TseModeApplyStatus.OPERATIONAL,
            message = "saved",
        )
    }

    // --- every other interface member is unused in these tests ---

    override suspend fun getStatus(): TseDto.StatusInfoDto = TODO()

    override suspend fun getInfo(): TseDto.TseInfoDto = TODO()

    override suspend fun getHealth(): TseDto.HealthCheckResultDto = TODO()

    override suspend fun getFlashHealth(): TseDto.FlashHealthResultDto = TODO()

    // Unavailable, so a TseSettingsViewModel built on this fake does not go on to load status/info.
    override suspend fun ping(): Map<String, Boolean> = mapOf("available" to false)

    override suspend fun runSelfTest(): TseDto.SelfTestResultDto = TODO()

    override suspend fun updateTime(): TseDto.TimeUpdateResultDto = TODO()

    override suspend fun restartTse(): TseDto.AdminResultDto = TODO()

    override suspend fun factoryReset(): TseDto.AdminResultDto = TODO()

    override suspend fun exportFull(): TseDto.ExportResultDto = TODO()

    override suspend fun exportIncremental(request: TseDto.IncrementalExportRequestDto) = TODO()

    // --- time-filtered export (F69 "Kasse" selector) ---
    var timeFilteredRequests = mutableListOf<TseDto.TimeFilteredExportRequestDto>()

    override suspend fun exportTimeFiltered(request: TseDto.TimeFilteredExportRequestDto): TseDto.ExportResultDto {
        timeFilteredRequests += request
        return TseDto.ExportResultDto(success = true, exportType = "filtered_time", message = "exported")
    }

    override suspend fun getExportSize(): TseDto.ExportSizeDto = TODO()

    // --- archive register (real-TSE QA rt14) ---
    var deleteResult: TseDto.ExportAndDeleteResultDto? = null
    val registeredFileNames = mutableListOf<String>()
    var registerError: (() -> Nothing)? = null

    override suspend fun deleteExportedData(): TseDto.ExportAndDeleteResultDto = deleteResult ?: TODO()

    override suspend fun registerExportArchive(fileName: String): TseDto.ExportArchiveDto {
        registeredFileNames += fileName
        registerError?.let { it() }
        return TseDto.ExportArchiveDto(
            id = registeredFileNames.size.toLong(),
            fileName = fileName,
            tseSerialHex = "4AB62B2F49710D4D",
            tanrFrom = 0,
            tanrTo = 100,
            sizeBytes = 782_336,
            sha256 = "52c566ce",
            origin = TseExportArchiveOrigin.REGISTERED,
            createdAt = "2026-05-06T17:30:00",
        )
    }

    override suspend fun checkFirmwareUpdate(): TseDto.FirmwareUpdateCheckResultDto = TODO()

    override suspend fun applyBundledFirmwareUpdate(): TseDto.FirmwareApplyResultDto = TODO()

    override suspend fun decommission(): TseDto.AdminResultDto = TODO()

    override suspend fun disableExportIfCspTestFails(): TseDto.AdminResultDto = TODO()

    override suspend fun browseEntries(request: TseDto.LogEntryBrowseRequestDto) = TODO()

    override suspend fun exportLcm(): TseDto.LcmExportResultDto = TODO()

    override suspend fun getClients(): TseDto.ClientListResultDto = TODO()

    override suspend fun getTransactions(): TseDto.TransactionListResultDto = TODO()

    override suspend fun getLastTransaction(): TseDto.LastTransactionResultDto = TODO()

    override suspend fun verifyExport(fileName: String): TseVerificationDto.ReportDto = TODO()
}
