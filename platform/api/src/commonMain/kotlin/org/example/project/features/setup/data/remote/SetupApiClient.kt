/**
 * API client interface for initial system setup operations.
 *
 * Defines the contract for checking system initialization status
 * and performing atomic complete setup. These endpoints are
 * unauthenticated since no user exists before setup.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.setup.data.remote

import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.dto.setup.SetupReadinessDto

/**
 * Client interface for initial setup API endpoints.
 *
 * Provides methods to check system initialization status and
 * execute complete setup from the UI wizard.
 *
 * Throws [org.example.project.core.network.error.PosApiException] for non-2xx responses
 * and [org.example.project.core.network.error.NetworkApiException] for transport failures.
 */
interface SetupApiClient {
    /**
     * Checks whether the system has been initialized.
     *
     * Calls GET /api/setup/status to determine if the system
     * requires initial setup.
     */
    suspend fun checkSetupStatus(): InitialSetupDto.InitialSetupStatusDto

    /**
     * Whether the install can actually take an order — a paired fiscal terminal, a commissioned TSE,
     * VAT rates, payment types, products, tables.
     *
     * Calls GET /api/setup/readiness, which unlike `/status` requires MANAGER+ because it reports
     * fiscal signing state.
     */
    suspend fun getReadiness(): SetupReadinessDto

    /**
     * Executes complete atomic system setup.
     *
     * Calls POST /api/setup/initialize with owner and shop data.
     * Server auto-configures payment types, VAT rates, and all defaults.
     *
     * @param request Combined owner and shop setup data
     */
    suspend fun executeCompleteSetup(request: InitialSetupDto.CompleteSetupRequestDto): InitialSetupDto.InitialSetupResponseDto

    suspend fun healthCheck()

    /**
     * Restores the system from a backup ZIP archive during initial setup.
     *
     * Only available when the system is not yet initialized.
     * Bypasses authentication since no user exists before setup.
     *
     * @param backupData ZIP archive content as ByteArray
     */
    suspend fun restoreBackupForSetup(backupData: ByteArray): BackupRestoreDto.RestoreResultDto
}
