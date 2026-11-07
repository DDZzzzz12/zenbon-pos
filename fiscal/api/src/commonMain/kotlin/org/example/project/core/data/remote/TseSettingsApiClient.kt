/**
 * API client interface for TSE (Technical Security Element) settings operations.
 *
 * Stage C Batch 8: throws-style.
 *
 * Implementations:
 * - [org.example.project.core.data.remote.impl.KtorTseSettingsApiClient] (HTTP client via Ktor)
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.dto.tse.TseVerificationDto

interface TseSettingsApiClient {
    // Configuration
    suspend fun getConfig(): TseConfigDto.TseConfig

    suspend fun updateConfig(request: TseConfigDto.UpdateTseConfigRequest): TseConfigDto.TseConfigUpdateResultDto

    // Status & Info
    suspend fun getStatus(): TseDto.StatusInfoDto

    suspend fun getInfo(): TseDto.TseInfoDto

    suspend fun getHealth(): TseDto.HealthCheckResultDto

    suspend fun getFlashHealth(): TseDto.FlashHealthResultDto

    suspend fun ping(): Map<String, Boolean>

    // Admin Operations
    suspend fun runSelfTest(): TseDto.SelfTestResultDto

    suspend fun updateTime(): TseDto.TimeUpdateResultDto

    /** Starts an admin sitting by handing the operator-supplied PIN to the server (memory only). */
    suspend fun unlockAdmin(pin: String): TseDto.TseAdminSessionStatusDto

    /** Ends the admin sitting immediately. */
    suspend fun lockAdmin(): TseDto.TseAdminSessionStatusDto

    /** Whether an admin sitting is currently live. */
    suspend fun adminSessionStatus(): TseDto.TseAdminSessionStatusDto

    suspend fun setup(credentials: TseDto.SetupCredentialsRequestDto): TseDto.AdminResultDto

    suspend fun restartTse(): TseDto.AdminResultDto

    suspend fun factoryReset(): TseDto.AdminResultDto

    // Export
    suspend fun exportFull(): TseDto.ExportResultDto

    suspend fun exportIncremental(request: TseDto.IncrementalExportRequestDto): TseDto.ExportResultDto

    suspend fun exportTimeFiltered(request: TseDto.TimeFilteredExportRequestDto): TseDto.ExportResultDto

    suspend fun getExportSize(): TseDto.ExportSizeDto

    suspend fun deleteExportedData(): TseDto.ExportAndDeleteResultDto

    /** Enters a TAR already in the server's TSE export folder in the TSE archive register. */
    suspend fun registerExportArchive(fileName: String): TseDto.ExportArchiveDto

    // Firmware
    suspend fun checkFirmwareUpdate(): TseDto.FirmwareUpdateCheckResultDto

    suspend fun applyBundledFirmwareUpdate(): TseDto.FirmwareApplyResultDto

    // Decommission
    suspend fun decommission(): TseDto.AdminResultDto

    // CSP Export
    suspend fun disableExportIfCspTestFails(): TseDto.AdminResultDto

    // Log Entry Browsing
    suspend fun browseEntries(request: TseDto.LogEntryBrowseRequestDto): TseDto.LogEntryBrowseResultDto

    // LCM Export
    suspend fun exportLcm(): TseDto.LcmExportResultDto

    // Client & Transaction List
    suspend fun getClients(): TseDto.ClientListResultDto

    suspend fun getTransactions(): TseDto.TransactionListResultDto

    suspend fun getLastTransaction(): TseDto.LastTransactionResultDto

    // Log-sequence plausibility verification (AGD §5.2 / OE.Transaction)

    /** Verifies a previously exported TAR that lives inside the server's resolved TSE exports dir. */
    suspend fun verifyExport(fileName: String): TseVerificationDto.ReportDto
}
