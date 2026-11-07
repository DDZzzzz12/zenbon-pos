package org.example.project.testutils.fakes

import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.dto.setup.SetupReadinessDto
import org.example.project.core.network.error.NetworkApiException
import org.example.project.features.setup.data.remote.SetupApiClient

class FakeSetupApiClient : SetupApiClient {
    /** Result for [checkSetupStatus]. Default: throws to mimic server-error. */
    var checkSetupStatusResult: Result<InitialSetupDto.InitialSetupStatusDto> =
        Result.failure(NetworkApiException(RuntimeException("server error")))
    var executeCompleteSetupResult: Result<InitialSetupDto.InitialSetupResponseDto> =
        Result.failure(NetworkApiException(RuntimeException("server error")))
    var healthCheckResult: Result<Unit> = Result.success(Unit)
    var restoreBackupResult: Result<BackupRestoreDto.RestoreResultDto> =
        Result.failure(NetworkApiException(RuntimeException("server error")))

    var healthCheckCallCount = 0
    var checkSetupStatusCallCount = 0
    var executeCompleteSetupCallCount = 0
    var restoreBackupCallCount = 0

    override suspend fun checkSetupStatus(): InitialSetupDto.InitialSetupStatusDto {
        checkSetupStatusCallCount++
        return checkSetupStatusResult.getOrThrow()
    }

    override suspend fun executeCompleteSetup(request: InitialSetupDto.CompleteSetupRequestDto): InitialSetupDto.InitialSetupResponseDto {
        executeCompleteSetupCallCount++
        return executeCompleteSetupResult.getOrThrow()
    }

    /**
     * Optional suspension point inside [healthCheck]. Lets a test hold a probe open and inspect the
     * state *while it is in flight*, instead of watching StateFlow emissions — StateFlow conflates,
     * so a transient value can be dropped and a real flicker can go unnoticed.
     */
    var onHealthCheck: (suspend () -> Unit)? = null

    override suspend fun healthCheck() {
        healthCheckCallCount++
        onHealthCheck?.invoke()
        healthCheckResult.getOrThrow()
    }

    override suspend fun restoreBackupForSetup(backupData: ByteArray): BackupRestoreDto.RestoreResultDto {
        restoreBackupCallCount++
        return restoreBackupResult.getOrThrow()
    }

    /** Not exercised by these tests — the readiness card has its own fake. */
    override suspend fun getReadiness(): SetupReadinessDto = throw NotImplementedError("getReadiness not used in this fake")
}
