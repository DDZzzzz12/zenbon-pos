package org.example.project.testutils.fakes

import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.network.error.NetworkApiException
import org.example.project.features.setup.data.remote.SetupApiClient

/**
 * Shared fake [SetupApiClient] for launch/auth flow tests. Extracted from
 * `LaunchViewModelTest` so `LaunchCoordinatorTest` can reuse the same fake body.
 *
 * Named `FakeLaunchSetupApiClient` (not `FakeSetupApiClient`) to avoid colliding with the
 * differently-shaped `FakeSetupApiClient` already declared in `commonTest/testutils/fakes`,
 * which `desktopTest` inherits.
 */
class FakeLaunchSetupApiClient : SetupApiClient {
    var status: InitialSetupDto.InitialSetupStatusDto =
        InitialSetupDto.InitialSetupStatusDto(
            isInitialized = true,
            requiresSetup = false,
        )
    var throwNetwork: Boolean = false

    /** How often [checkSetupStatus] was called — each real call would send the device token. */
    var checkSetupStatusCalls: Int = 0
        private set

    /** Overrides [throwNetwork] when set, e.g. to make the server answer at one address only. */
    var reachable: (() -> Boolean)? = null

    /** When set, thrown by [checkSetupStatus] before anything else, e.g. a TLS trust failure. */
    var failure: Throwable? = null

    /** When true, [checkSetupStatus] never answers — an address that swallows packets. */
    var hang: Boolean = false

    override suspend fun checkSetupStatus(): InitialSetupDto.InitialSetupStatusDto {
        checkSetupStatusCalls++
        if (hang) kotlinx.coroutines.awaitCancellation()
        failure?.let { throw it }
        if (!(reachable?.invoke() ?: !throwNetwork)) throw NetworkApiException(RuntimeException("offline"))
        return status
    }

    override suspend fun getReadiness() = TODO()

    override suspend fun executeCompleteSetup(request: InitialSetupDto.CompleteSetupRequestDto): InitialSetupDto.InitialSetupResponseDto = TODO()

    override suspend fun healthCheck() = TODO()

    override suspend fun restoreBackupForSetup(backupData: ByteArray): BackupRestoreDto.RestoreResultDto = TODO()
}
