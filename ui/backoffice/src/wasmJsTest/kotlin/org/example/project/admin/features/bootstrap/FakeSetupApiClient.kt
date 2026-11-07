package org.example.project.admin.features.bootstrap

import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.dto.setup.SetupReadinessDto
import org.example.project.features.setup.data.remote.SetupApiClient

/**
 * Canonical adminApp `wasmJsTest` double for the shared [SetupApiClient] (Terminal Phase RB3).
 * Throws-style: returns a configured value, or invokes a configured `(() -> Nothing)?` error hook.
 */
class FakeSetupApiClient : SetupApiClient {
    var statusResult: InitialSetupDto.InitialSetupStatusDto =
        InitialSetupDto.InitialSetupStatusDto(isInitialized = false, requiresSetup = true)
    var statusError: (() -> Nothing)? = null
    var statusCallCount: Int = 0

    var submitResult: InitialSetupDto.InitialSetupResponseDto =
        InitialSetupDto.InitialSetupResponseDto(
            success = true,
            message = "ok",
            setupSummary = InitialSetupDto.SetupSummaryDto(shopInfoConfigured = true),
            completedAt = "2026-07-26T10:00:00",
            durationMs = 1L,
        )
    var submitError: (() -> Nothing)? = null
    var lastSubmitRequest: InitialSetupDto.CompleteSetupRequestDto? = null

    var restoreResult: BackupRestoreDto.RestoreResultDto =
        BackupRestoreDto.RestoreResultDto(
            success = true,
            message = "restored",
            restoredAt = "2026-07-28T10:00:00",
        )
    var restoreError: (() -> Nothing)? = null
    var lastRestoreBytes: ByteArray? = null
    var restoreCallCount: Int = 0

    override suspend fun checkSetupStatus(): InitialSetupDto.InitialSetupStatusDto {
        statusCallCount++
        statusError?.let { it() }
        return statusResult
    }

    override suspend fun executeCompleteSetup(request: InitialSetupDto.CompleteSetupRequestDto): InitialSetupDto.InitialSetupResponseDto {
        lastSubmitRequest = request
        submitError?.let { it() }
        return submitResult
    }

    override suspend fun healthCheck() {}

    override suspend fun restoreBackupForSetup(backupData: ByteArray): BackupRestoreDto.RestoreResultDto {
        restoreCallCount++
        lastRestoreBytes = backupData
        restoreError?.let { it() }
        return restoreResult
    }

    /** Not exercised by these tests — the readiness card has its own fake. */
    override suspend fun getReadiness(): SetupReadinessDto = throw NotImplementedError("getReadiness not used in this fake")
}
