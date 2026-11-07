/**
 * PROTECTED MODULE — Do not modify without explicit authorization.
 * TSE operations are fiscally regulated (KassenSichV) and changes require careful review.
 *
 * Provider interface for TSE administrative operations.
 *
 * Abstracts the admin communication path. The only topology is direct local
 * hardware access via the embedded server-tse module.
 *
 * Implementation:
 * - [org.example.project.features.tse.data.provider.local.LocalTseAdminProvider]
 *
 * @since 1.0.0
 */
package org.example.project.features.tse.domain.provider

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.infrastructure.errors.PosException

/**
 * Provider interface for TSE administrative operations.
 *
 * All admin methods throw [PosException] on failure (B1 amendment).
 * The error code is one of the TSE_* entries in
 * [org.example.project.core.error.PosErrorCode] per the cross-walk table.
 */
interface TseAdminProvider {
    /**
     * Get TSE status information.
     *
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun getStatus(): TseDto.StatusInfoDto

    /**
     * Perform health check on TSE.
     *
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun healthCheck(): TseDto.HealthCheckResultDto

    /**
     * Simple ping check for TSE availability.
     *
     * @return true if TSE responds, false otherwise
     */
    suspend fun ping(): Boolean

    /**
     * Get TSE status from admin endpoint.
     *
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun status(): TseDto.StatusInfoDto

    /**
     * Get detailed TSE information.
     *
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun getInfo(): TseDto.TseInfoDto

    /**
     * Get flash memory health summary.
     *
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun flashHealth(): TseDto.FlashHealthResultDto

    /**
     * Initialize/setup TSE hardware.
     *
     * @param deviceId Device ID to lookup terminal for client registration
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun setup(
        deviceId: String,
        credentials: TseDto.SetupCredentialsRequestDto,
    ): TseDto.AdminResultDto

    /**
     * Factory reset the TSE (development firmware only).
     *
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun factoryReset(): TseDto.AdminResultDto

    /**
     * Run TSE self-test.
     *
     * @param clientId Optional client ID for the test
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun selfTest(clientId: String? = null): TseDto.SelfTestResultDto

    /**
     * Force TSE self-test execution.
     *
     * @param clientId Optional client ID for the test
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun forceSelfTest(clientId: String? = null): TseDto.SelfTestResultDto

    /**
     * Synchronize TSE time with system time.
     *
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun updateTime(): TseDto.TimeUpdateResultDto

    /**
     * Register a client ID with the TSE.
     *
     * @param clientId Client ID to register
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun registerClient(clientId: String): TseDto.ClientRegistrationResultDto

    /**
     * Deregister a client ID from the TSE.
     *
     * @param clientId Client ID to deregister
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun deregisterClient(clientId: String): TseDto.AdminResultDto

    /**
     * List all registered clients.
     *
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun listClients(): TseDto.ClientListResultDto

    /**
     * List all started (unfinished) transactions.
     *
     * @param clientId Optional client ID filter
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun listTransactions(clientId: String? = null): TseDto.TransactionListResultDto

    /**
     * Get the last transaction response.
     *
     * @param clientId Optional client ID filter
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun lastTransaction(clientId: String? = null): TseDto.LastTransactionResultDto

    /**
     * Export TSE data as TAR archive (simple full export for backward compat).
     *
     * @param outputPath Optional output path for the export
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun exportLocal(outputPath: String): TseDto.LocalExportResultDto

    /**
     * Perform a full TSE TAR export.
     *
     * @param outputPath Directory path for export files
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun exportFull(outputPath: String = "exports"): TseDto.ExportResultDto

    /**
     * Perform an incremental TSE TAR export.
     *
     * @param outputPath Directory path for export files
     * @param lastState Base64-encoded previous incremental export state (null for first export)
     * @param maxExportSize Maximum export size in bytes (0 = unlimited)
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun exportIncremental(
        outputPath: String = "exports",
        lastState: String? = null,
        maxExportSize: Long = 0,
    ): TseDto.ExportResultDto

    /**
     * Perform a time-filtered TSE TAR export.
     *
     * @param outputPath Directory path for export files
     * @param startDate Start timestamp in Unix milliseconds
     * @param endDate End timestamp in Unix milliseconds
     * @param clientId TSE client to filter on; null exports every client (F69). Validated by the
     *   caller ([org.example.project.features.tse.data.service.FilteredExportClientValidator]).
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun exportTimeFiltered(
        outputPath: String = "exports",
        startDate: Long,
        endDate: Long,
        clientId: String? = null,
    ): TseDto.ExportResultDto

    /**
     * Perform a transaction-filtered TSE TAR export.
     *
     * @param outputPath Directory path for export files
     * @param startTransaction Start transaction number
     * @param endTransaction End transaction number
     * @param clientId TSE client to filter on; null exports every client (F69). Validated by the
     *   caller ([org.example.project.features.tse.data.service.FilteredExportClientValidator]).
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun exportTransactionFiltered(
        outputPath: String = "exports",
        startTransaction: Long,
        endTransaction: Long,
        clientId: String? = null,
    ): TseDto.ExportResultDto

    /**
     * Calculate export size without performing the export.
     *
     * @param lastState Base64-encoded previous incremental export state (null for first query)
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun getExportSize(lastState: String? = null): TseDto.ExportSizeDto

    /**
     * Export everything from the TSE into a TAR under [outputPath], then delete it from the device.
     *
     * One sitting: Admin login, full export (which includes that login's log entry), archive
     * check, delete, logout. Nothing is deleted unless the archive is written. The archive is the
     * only remaining copy of the deleted entries.
     *
     * @param outputPath Directory the archive is written to
     * @throws PosException on TSE failure, naming the step that failed
     */
    @Throws(PosException::class)
    suspend fun deleteExportedData(outputPath: String): TseDto.ExportAndDeleteResultDto

    /**
     * Unblock a user with PUK.
     *
     * @param puk The PUK code for unblocking
     * @param userId Optional user ID (defaults to ADMIN)
     * @param newPin Optional new PIN to set
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun unblockUser(
        puk: String,
        userId: String? = null,
        newPin: String? = null,
    ): TseDto.AdminResultDto

    /**
     * Change user PIN.
     *
     * @param currentPin Current PIN
     * @param newPin New PIN to set
     * @param userId Optional user ID (defaults to ADMIN)
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun changePin(
        currentPin: String,
        newPin: String,
        userId: String? = null,
    ): TseDto.AdminResultDto

    /**
     * Enable CTSS interface.
     *
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun enableCtss(): TseDto.AdminResultDto

    /**
     * Disable CTSS interface.
     *
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun disableCtss(): TseDto.AdminResultDto

    /**
     * Check for available TSE firmware updates.
     *
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun checkFirmwareUpdate(): TseDto.FirmwareUpdateCheckResultDto

    /**
     * Apply bundled firmware update to TSE.
     *
     * WARNING: Long-running operation. Power loss may brick device.
     *
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun applyBundledFirmwareUpdate(): TseDto.FirmwareApplyResultDto

    /**
     * Decommission the TSE.
     *
     * WARNING: IRREVERSIBLE operation. Only undone by factory reset (dev firmware).
     *
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun decommission(): TseDto.AdminResultDto

    /**
     * Disable export if CSP self-test fails.
     *
     * Security hardening: prevents data export when the Crypto Service Provider
     * self-test fails.
     *
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun disableExportIfCspTestFails(): TseDto.AdminResultDto

    /**
     * Browse TSE log entries.
     *
     * @param request Browse request with direction, entry ID, and count
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun browseEntries(request: TseDto.LogEntryBrowseRequestDto): TseDto.LogEntryBrowseResultDto

    /**
     * Export LCM diagnostics data from TSE.
     *
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun exportLcm(): TseDto.LcmExportResultDto

    /**
     * Restart the TSE server/hardware connection.
     *
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun restartServer(): TseDto.AdminResultDto

    /**
     * Log the Admin user out of the TSE, ending the device-side sitting.
     *
     * Called when the operator's in-memory admin session ends (explicit lock or idle expiry).
     * Idempotent — a no-op when nobody is logged in.
     */
    suspend fun logoutAdmin(): TseDto.AdminResultDto

    /**
     * Refresh the cached TSE configuration.
     */
    suspend fun refreshConfig()

    /**
     * Update the TSE operating mode.
     *
     * @param mode The mode to set (NORMAL or SIMULATION)
     * @param clientId TSE client ID
     * @throws PosException on TSE failure
     */
    @Throws(PosException::class)
    suspend fun setMode(
        mode: String,
        clientId: String,
    ): Boolean

    /**
     * Check if TSE is initialized.
     *
     * @return true if TSE is initialized, false otherwise
     */
    suspend fun isInitialized(): Boolean
}
