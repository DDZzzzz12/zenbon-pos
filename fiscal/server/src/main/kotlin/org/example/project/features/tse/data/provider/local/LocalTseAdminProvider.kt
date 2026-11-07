/**
 * Local implementation of [TseAdminProvider] for direct TSE hardware administration.
 *
 * Executes administrative operations directly against the TSE hardware via server-tse
 * operation classes, bypassing the HTTP layer entirely.
 *
 * Architecture:
 * - Main Server → LocalTseAdminProvider → AdminOperation.execute(TseStatus) → WormAPI → TSE Hardware
 *
 * Error Handling (B1 final, post-Task-5):
 * - Underlying [org.example.project.tse.operations] throw [PosException] directly
 *   per the B1 cross-walk.
 * - Each public method delegates via [runOperation], which wraps unexpected
 *   exceptions as `PosException(TSE_OPERATION_FAILED, …, cause = e)`.
 *
 * @param lifecycleManager Manages the TseStatus instance and hardware lifecycle
 * @param terminalRepository Repository for terminal lookup (the setup terminal)
 * @param capabilityResolver Terminal capability cache, invalidated when setup registers a bound terminal
 *
 * @see org.example.project.features.tse.domain.provider.TseAdminProvider
 * @since 1.0.0
 */
package org.example.project.features.tse.data.provider.local

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.TseCredentialPlaceholders
import org.example.project.features.terminal.data.service.TerminalCapabilityResolver
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.features.tse.data.service.TseDeviceInfoCache
import org.example.project.features.tse.data.service.requireTimeAdminPinConfigured
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.features.tse.domain.session.TseAdminSession
import org.example.project.features.tse.verification.requireTseArchiveRoom
import org.example.project.features.tse.verification.requireTseArchiveWritable
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.clients.DeregisterClientOperation
import org.example.project.tse.operations.admin.clients.ListClientsOperation
import org.example.project.tse.operations.admin.clients.RegisterClientOperation
import org.example.project.tse.operations.admin.csp.DisableExportIfCspTestFailsOperation
import org.example.project.tse.operations.admin.ctss.CtssDisableOperation
import org.example.project.tse.operations.admin.ctss.CtssEnableOperation
import org.example.project.tse.operations.admin.entries.BrowseEntriesOperation
import org.example.project.tse.operations.admin.export.DeleteExportedDataOperation
import org.example.project.tse.operations.admin.export.ExportLocalOperation
import org.example.project.tse.operations.admin.export.ExportSizeOperation
import org.example.project.tse.operations.admin.export.FullExportOperation
import org.example.project.tse.operations.admin.export.IncrementalExportOperation
import org.example.project.tse.operations.admin.export.LcmExportOperation
import org.example.project.tse.operations.admin.export.TimeFilteredExportOperation
import org.example.project.tse.operations.admin.export.TransactionFilteredExportOperation
import org.example.project.tse.operations.admin.firmware.FirmwareApplyBundledOperation
import org.example.project.tse.operations.admin.firmware.FirmwareUpdateCheckOperation
import org.example.project.tse.operations.admin.setup.DecommissionOperation
import org.example.project.tse.operations.admin.setup.FactoryResetOperation
import org.example.project.tse.operations.admin.setup.SetupOperation
import org.example.project.tse.operations.admin.status.FlashHealthOperation
import org.example.project.tse.operations.admin.status.GetInfoOperation
import org.example.project.tse.operations.admin.status.HealthCheckOperation
import org.example.project.tse.operations.admin.status.StatusOperation
import org.example.project.tse.operations.admin.time.UpdateTimeOperation
import org.example.project.tse.operations.admin.transactions.LastTransactionOperation
import org.example.project.tse.operations.admin.transactions.ListTransactionsOperation
import org.example.project.tse.operations.admin.users.ChangePinOperation
import org.example.project.tse.operations.admin.users.LogoutAdminOperation
import org.example.project.tse.operations.admin.users.UserUnblockOperation
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Base64

class LocalTseAdminProvider(
    private val lifecycleManager: LocalTseLifecycleManager,
    private val terminalRepository: TerminalRepository,
    private val capabilityResolver: TerminalCapabilityResolver,
    // Test seams for setup's mode handling. Production drives the real device and the persisted
    // shop configuration; null selects the device this provider's own lifecycle manager owns.
    commissioningDevice: TseCommissioningDevice? = null,
    private val readTseConfig: suspend () -> TseConfigDto.TseConfig = { ShopConfigurationManager.getTseConfig() },
    private val writeTseConfig: suspend (TseConfigDto.TseConfig) -> Unit = { ShopConfigurationManager.setTseConfig(it) },
) : TseAdminProvider {
    private val logger = LoggerFactory.getLogger(LocalTseAdminProvider::class.java)
    private val setupTerminal = TseSetupTerminal(terminalRepository)
    private val exportNaming = Mutex()
    private var lastExportStartMillis = 0L
    private val commissioningDevice: TseCommissioningDevice = commissioningDevice ?: localCommissioningDevice()

    /** The attached Swissbit TSE, driven through the `pos-tse` operations. */
    private fun localCommissioningDevice(): TseCommissioningDevice =
        object : TseCommissioningDevice {
            override fun open(): Boolean = lifecycleManager.attachForCommissioning()

            override suspend fun isCommissioned(): Boolean = isInitialized()

            override fun commission(request: TseCommissioningRequest): TseDto.AdminResultDto =
                SetupOperation(
                    adminPin = request.adminPin,
                    adminPuk = request.adminPuk,
                    timeAdminPin = request.timeAdminPin,
                    mode = request.mode,
                    clientId = request.clientId,
                    // The lifecycle manager's managed task: tracked, and stopped on close.
                    maintenanceTask = lifecycleManager.getOrCreateMaintenanceTask(),
                    credentialSeed = request.credentialSeed,
                ).execute(lifecycleManager.getTseStatus())
        }

    /** The manual "Selbsttest": the same self test the power-loss recovery runs. */
    private val manualSelfTest = LocalTseSelfTest.forLocalTse(lifecycleManager, maintenanceOp = "manual-selftest")

    /**
     * Gets the admin PIN from the current TSE configuration.
     */
    /**
     * The operator-supplied Admin PIN for the current admin sitting.
     *
     * Deliberately NOT read from configuration: the Admin PIN is no longer stored, per the
     * Swissbit guidance manual (2.5). Every operation that needs it is operator-triggered, so
     * a missing session simply means TSE administration has not been unlocked yet.
     */

    /**
     * Passed where the SDK requires no login at all.
     *
     * These operations never authenticate: the self test, the exports and the various read-only
     * queries are permitted unauthenticated (export_tar merely excludes TimeAdmin), and time
     * sync logs in as TimeAdmin itself. Reading the admin session for them would demand an
     * operator-supplied PIN for work that must run unattended — the scheduled self test, the
     * scheduled export, the storage-headroom check.
     */
    private val NO_ADMIN_PIN: String = ""

    private fun getAdminPin(): String =
        TseAdminSession.pinOrNull() ?: throw PosException(
            code = PosErrorCode.TSE_ADMIN_LOCKED,
            // 423 Locked, not 401. A 401 makes the client's Ktor Auth plugin refresh the JWT
            // and silently retry — which loops against a locked TSE and eats the error before
            // the UI can prompt for the PIN. 423 says exactly what is true: the resource is
            // locked, unlock it.
            httpStatus = HttpStatusCode.Locked,
            message = "TSE administration is locked. Enter the Admin PIN to continue.",
        )

    /**
     * A commissioned TSE is not set up again. The device only re-ran its startup sequence and wrote
     * nothing, yet the call answered "completed successfully" — so an operator re-entering a PIN,
     * PUK or seed was told they took effect when none did (E2E round 4, R4-3). Re-initialising the
     * connection is `PUT /api/tse/config`; registering a terminal is `tse-register`.
     */
    private suspend fun refuseRecommissioning() {
        if (!commissioningDevice.isCommissioned()) return
        throw PosException(
            code = PosErrorCode.TSE_ALREADY_INITIALIZED,
            httpStatus = HttpStatusCode.Conflict,
            message =
                "The TSE is already commissioned. Nothing was written; the Admin PIN, PUK and seed " +
                    "given here were not applied.",
        )
    }

    /**
     * The placeholders `pos-tse` refuses in its log only, refused here with the field named and
     * before anything else happens — in particular before a failed setup switches the mode to
     * DISABLED (E2E round 4, R4-13).
     */
    private fun refusePlaceholderCredentials(
        credentials: TseDto.SetupCredentialsRequestDto,
        timeAdminPin: String,
    ) {
        val placeholders =
            TseCredentialPlaceholders.placeholderFields(credentials.adminPin, credentials.adminPuk, timeAdminPin)
        if (placeholders.isEmpty()) return
        throw PosException(
            code = PosErrorCode.TSE_INVALID_PARAMETERS,
            httpStatus = HttpStatusCode.BadRequest,
            message =
                "${placeholders.joinToString(", ")} still set to a factory placeholder. Choose real values — " +
                    "setup writes them to the device one-way. Nothing has been written to the device.",
        )
    }

    // ============================================================================================
    // Status Endpoints
    // ============================================================================================

    override suspend fun getStatus(): TseDto.StatusInfoDto =
        runOperation("getStatus") {
            // m3: capture the device's log-time format for the signing persist (no device I/O there).
            StatusOperation()
                .execute(lifecycleManager.getTseStatus())
                .also { TseDeviceInfoCache.recordLogTimeFormat(it.logTimeFormat) }
        }

    override suspend fun healthCheck(): TseDto.HealthCheckResultDto {
        val tseStatus = lifecycleManager.getTseStatus()
        val raw =
            runOperation("healthCheck") {
                HealthCheckOperation().execute(tseStatus)
            }
        // Same derivation as the sign provider's checkHealth. Without it the DTO defaults applied
        // and an unhealthy TSE answered "health":"HEALTHY" with no maintenance ops (F50).
        val inMaintenance = tseStatus.isInMaintenance()
        val ops = if (inMaintenance) tseStatus.currentMaintenanceOps() else emptySet()
        val health = deriveTseHealth(readTseConfig().mode, raw, inMaintenance)
        return raw.withDerivedHealth(health, ops).withCertificateExpiry(readCertificateExpiry())
    }

    /**
     * The certificate expiry `GET /api/tse/info` reports, for the health result.
     *
     * The driver's health check reads the device info but does not copy the expiry into its result,
     * so `/health` answered 0 while `/info` answered the real date (found on a real Swissbit TSE).
     * One unauthenticated info read, like `/info`'s own. Null when it fails: the health verdict
     * still stands, and the field keeps its documented 0 = unknown.
     */
    private suspend fun readCertificateExpiry(): Long? =
        try {
            getInfo().certificateExpirationDate
        } catch (e: PosException) {
            logger.warn("TSE health check: certificate expiry unavailable (${e.message})")
            null
        }

    override suspend fun logoutAdmin(): TseDto.AdminResultDto =
        runOperation("logoutAdmin") {
            LogoutAdminOperation().execute(lifecycleManager.getTseStatus())
        }

    override suspend fun ping(): Boolean =
        try {
            lifecycleManager.getTseStatus().isConnected()
        } catch (e: Exception) {
            logger.warn("Local TSE ping failed: ${e.message}")
            false
        }

    override suspend fun status(): TseDto.StatusInfoDto =
        runOperation("status") {
            // m3: capture the device's log-time format for the signing persist (no device I/O there).
            StatusOperation()
                .execute(lifecycleManager.getTseStatus())
                .also { TseDeviceInfoCache.recordLogTimeFormat(it.logTimeFormat) }
        }

    override suspend fun getInfo(): TseDto.TseInfoDto =
        runOperation("getInfo") {
            // Fix A: the attached device's identity, learned WITHOUT signing. The signer refuses to
            // finish a stored transaction number on a device it cannot identify, so after a restart
            // this read is what lets an interrupted Vorgang resume on its own TANR instead of being
            // superseded. No extra device I/O: this operation already ran for the status screen.
            GetInfoOperation(adminPin = NO_ADMIN_PIN)
                .execute(lifecycleManager.getTseStatus())
                .also { TseDeviceInfoCache.recordSerial(it.tseSerialNumberHex) }
        }

    override suspend fun flashHealth(): TseDto.FlashHealthResultDto =
        runOperation("flashHealth") {
            FlashHealthOperation(adminPin = NO_ADMIN_PIN).execute(lifecycleManager.getTseStatus())
        }

    // ============================================================================================
    // Setup Endpoints
    // ============================================================================================

    override suspend fun setup(
        deviceId: String,
        credentials: TseDto.SetupCredentialsRequestDto,
    ): TseDto.AdminResultDto {
        // The operator's chosen terminal, else the caller's own; refused before the TSE is touched.
        val terminalId = setupTerminal.resolve(deviceId, credentials.terminalId)

        val config = readTseConfig()
        // NORMAL whether the shop was configured DISABLED (a fresh install) or NORMAL; a SIMULATION
        // shop is refused here, before anything is opened or written. See TseSetupMode.
        val mode = TseSetupMode.commissioningMode(config.mode)
        requireTimeAdminPinConfigured(config.timeAdminPin)
        refusePlaceholderCredentials(credentials, config.timeAdminPin)
        openForCommissioning()
        refuseRecommissioning()

        return try {
            logger.info("Setting up local TSE with clientId: $terminalId (configured mode ${config.mode}, commissioning in $mode)")
            val request =
                TseCommissioningRequest(
                    adminPin = credentials.adminPin,
                    adminPuk = credentials.adminPuk,
                    timeAdminPin = config.timeAdminPin,
                    credentialSeed = credentials.credentialSeed,
                    clientId = terminalId,
                    mode = mode,
                )

            val resultData =
                try {
                    commissioningDevice.commission(request)
                } catch (e: PosException) {
                    logger.error("Local TSE setup failed, setting mode to DISABLED")
                    runCatching { setMode("DISABLED", terminalId) }
                    throw e
                }

            // Setup itself registers the client: tse_setup_ext on v2, tse_registerClient inside
            // TseInitializationManager on v1 and on partial-setup recovery. A second registration here
            // needed an Admin PIN session setup never opens, and logged a misleading
            // "client registration failed: TSE administration is locked" on every commissioning (E2E R2-6).
            if (resultData.success) {
                logger.info("TSE setup succeeded; client '$terminalId' registered by the setup call")
                // Keep the flag in step with the device, as tse-register does. Best-effort: the TSE
                // is already commissioned, and sync-tse reconciles the flag if this write fails.
                runCatching { capabilityResolver.invalidate(setupTerminal.markRegistered(terminalId)) }
                    .onFailure { logger.warn("TSE setup: could not flag '$terminalId' as registered: ${it.message}") }
                persistCommissionedMode(mode, resultData)
            } else {
                logger.error("Local TSE setup returned unsuccessful: ${resultData.message}")
                runCatching { setMode("DISABLED", terminalId) }
                resultData
            }
        } catch (e: PosException) {
            throw e
        } catch (e: Exception) {
            logger.error("Local TSE setup exception", e)
            throw unexpected("TSE setup failed", e)
        }
    }

    /**
     * Opens the TSE for setup. A fresh install is configured DISABLED, so nothing opened it at start;
     * without this the setup sequence found no device to commission, and the "already commissioned"
     * check below could not read the device and let a commissioned TSE through.
     */
    private fun openForCommissioning() {
        if (commissioningDevice.open()) return
        throw PosException(
            code = PosErrorCode.TSE_HARDWARE_NOT_FOUND,
            httpStatus = HttpStatusCode.ServiceUnavailable,
            message = "No TSE was found. Check that it is plugged in, then try again. Nothing has been written to the device.",
        )
    }

    /**
     * Records the mode the TSE was just commissioned in as the shop's configured mode.
     *
     * The device now runs NORMAL; a configuration left at DISABLED would disagree with it, and the
     * signing guard refuses to sign while the two differ — every receipt would queue. Re-read
     * rather than reusing the copy taken before setup, so a concurrent edit of the other fields
     * survives. The TSE is commissioned either way, so a failed write is reported in the result
     * rather than thrown: an operator answered "failed" would retry and be told it is already set up.
     */
    private suspend fun persistCommissionedMode(
        mode: TseMode,
        result: TseDto.AdminResultDto,
    ): TseDto.AdminResultDto =
        try {
            val current = readTseConfig()
            if (current.mode != mode) {
                writeTseConfig(current.copy(mode = mode))
                logger.info("TSE setup: configured mode ${current.mode} -> $mode")
            }
            result
        } catch (e: Exception) {
            logger.error("TSE setup: the TSE is commissioned but the mode $mode could not be saved", e)
            result.copy(
                message =
                    "${result.message} The TSE mode could not be saved as $mode — set the mode to $mode in the TSE " +
                        "configuration, or receipts will queue for signing.",
            )
        }

    override suspend fun factoryReset(): TseDto.AdminResultDto =
        runOperation("factoryReset") {
            logger.warn("Factory reset requested on local TSE - this is destructive!")
            // No login: the operation checks for development firmware and the SDK resets without a PIN.
            // Demanding the Admin PIN here left a development TSE with a lost PIN impossible to reset.
            val tseStatus = lifecycleManager.getTseStatus()
            FactoryResetOperation(adminPin = NO_ADMIN_PIN).execute(tseStatus).also { result ->
                if (result.success) {
                    refreshAfterFactoryReset(tseStatus)
                    forgetTerminalRegistrations()
                }
            }
        }

    /** The reset emptied the TSE's client list, so the terminals' recorded registrations are all stale. */
    private suspend fun forgetTerminalRegistrations() {
        runCatching { setupTerminal.markAllUnregistered() }
            .onSuccess { capabilityResolver.invalidateAll() }
            .onFailure { logger.warn("Terminal registration flags could not be cleared after the factory reset: ${it.message}") }
    }

    /**
     * The driver caches the initialization state it read when it opened the device, so without this
     * `/api/tse/status` kept answering "initialized" after a reset while `/info` and `/health` (which read the
     * device) said uninitialized. Reconnecting re-opens the device and re-reads it; a failure here leaves the
     * reset itself successful.
     */
    private fun refreshAfterFactoryReset(tseStatus: TseStatus) {
        val reconnected = runCatching { tseStatus.reconnect() }.getOrElse { false }
        if (!reconnected) logger.warn("TSE could not be re-read after the factory reset; restart the TSE connection to refresh the status")
    }

    // ============================================================================================
    // Self-Test Endpoints
    // ============================================================================================

    // No client named (the route never names one): the registered terminal is resolved per run, so
    // the button works after the TSE was plugged in while the server ran (real Swissbit TSE 1.x,
    // 2026-10-10 — it used to fail like the recovery did). TSE 1.x with no registered terminal is
    // refused with a 409 the operator can act on, before any device call.
    override suspend fun selfTest(clientId: String?): TseDto.SelfTestResultDto =
        runOperation("selfTest") {
            logger.info("Running local TSE self-test")
            manualSelfTest.run(force = false, explicitClient = clientId)
        }

    override suspend fun forceSelfTest(clientId: String?): TseDto.SelfTestResultDto =
        runOperation("forceSelfTest") {
            logger.info("Running forced local TSE self-test")
            manualSelfTest.run(force = true, explicitClient = clientId)
        }

    // ============================================================================================
    // Time Endpoints
    // ============================================================================================

    override suspend fun updateTime(): TseDto.TimeUpdateResultDto =
        runOperation("updateTime") {
            logger.info("Updating local TSE time")
            lifecycleManager.getTseStatus().withMaintenanceSuspend("scheduled-timesync") {
                UpdateTimeOperation(adminPin = NO_ADMIN_PIN).execute(lifecycleManager.getTseStatus())
            }
        }

    // ============================================================================================
    // Client Management Endpoints
    // ============================================================================================

    override suspend fun registerClient(clientId: String): TseDto.ClientRegistrationResultDto =
        runOperation("registerClient") {
            logger.info("Registering local TSE client: $clientId")
            RegisterClientOperation(adminPin = getAdminPin(), clientId = clientId)
                .execute(lifecycleManager.getTseStatus())
        }

    override suspend fun deregisterClient(clientId: String): TseDto.AdminResultDto =
        runOperation("deregisterClient") {
            logger.info("Deregistering local TSE client: $clientId")
            DeregisterClientOperation(adminPin = getAdminPin(), clientId = clientId)
                .execute(lifecycleManager.getTseStatus())
        }

    override suspend fun listClients(): TseDto.ClientListResultDto =
        runOperation("listClients") {
            logger.debug("Listing local TSE clients")
            ListClientsOperation(adminPin = getAdminPin()).execute(lifecycleManager.getTseStatus())
        }

    // ============================================================================================
    // Transaction Query Endpoints
    // ============================================================================================

    override suspend fun listTransactions(clientId: String?): TseDto.TransactionListResultDto =
        runOperation("listTransactions") {
            logger.debug("Listing local TSE transactions")
            ListTransactionsOperation(adminPin = NO_ADMIN_PIN, clientId = clientId)
                .execute(lifecycleManager.getTseStatus())
        }

    override suspend fun lastTransaction(clientId: String?): TseDto.LastTransactionResultDto =
        runOperation("lastTransaction") {
            logger.debug("Getting last local TSE transaction")
            LastTransactionOperation(adminPin = NO_ADMIN_PIN, clientId = clientId)
                .execute(lifecycleManager.getTseStatus())
        }

    // ============================================================================================
    // Export Endpoints
    // ============================================================================================

    override suspend fun exportLocal(outputPath: String): TseDto.LocalExportResultDto =
        runOperation("exportLocal") {
            logger.info("Exporting local TSE data")
            ExportLocalOperation(adminPin = NO_ADMIN_PIN, outputPath = outputPath)
                .execute(lifecycleManager.getTseStatus())
        }

    override suspend fun exportFull(outputPath: String): TseDto.ExportResultDto =
        runOperation("exportFull") {
            distinctSecondExport {
                logger.info("Performing full TSE export to: $outputPath")
                FullExportOperation(adminPin = NO_ADMIN_PIN, outputPath = outputPath)
                    .execute(lifecycleManager.getTseStatus())
            }
        }

    override suspend fun exportIncremental(
        outputPath: String,
        lastState: String?,
        maxExportSize: Long,
    ): TseDto.ExportResultDto =
        runOperation("exportIncremental") {
            distinctSecondExport {
                logger.info("Performing incremental TSE export (hasState=${lastState != null})")
                val stateBytes = lastState?.let { Base64.getDecoder().decode(it) }
                IncrementalExportOperation(
                    adminPin = NO_ADMIN_PIN,
                    outputPath = outputPath,
                    lastState = stateBytes,
                    maxExportSize = maxExportSize,
                ).execute(lifecycleManager.getTseStatus())
            }
        }

    override suspend fun exportTimeFiltered(
        outputPath: String,
        startDate: Long,
        endDate: Long,
        clientId: String?,
    ): TseDto.ExportResultDto =
        filteredExport("exportTimeFiltered", outputPath, "the period $startDate–$endDate (epoch ms)", clientId) {
            logger.info("Performing time-filtered TSE export: $startDate to $endDate, client ${clientId ?: "all"}")
            TimeFilteredExportOperation(
                adminPin = NO_ADMIN_PIN,
                outputPath = outputPath,
                startDate = startDate,
                endDate = endDate,
                clientId = clientId,
            ).execute(lifecycleManager.getTseStatus())
        }

    override suspend fun exportTransactionFiltered(
        outputPath: String,
        startTransaction: Long,
        endTransaction: Long,
        clientId: String?,
    ): TseDto.ExportResultDto =
        filteredExport("exportTransactionFiltered", outputPath, "TANR $startTransaction–$endTransaction", clientId) {
            logger.info("Performing transaction-filtered TSE export: TX $startTransaction to $endTransaction, client ${clientId ?: "all"}")
            TransactionFilteredExportOperation(
                adminPin = NO_ADMIN_PIN,
                outputPath = outputPath,
                startTransaction = startTransaction,
                endTransaction = endTransaction,
                clientId = clientId,
            ).execute(lifecycleManager.getTseStatus())
        }

    override suspend fun getExportSize(lastState: String?): TseDto.ExportSizeDto =
        runOperation("getExportSize") {
            logger.info("Calculating TSE export size")
            val stateBytes = lastState?.let { Base64.getDecoder().decode(it) }
            ExportSizeOperation(adminPin = NO_ADMIN_PIN, lastState = stateBytes)
                .execute(lifecycleManager.getTseStatus())
        }

    override suspend fun deleteExportedData(outputPath: String): TseDto.ExportAndDeleteResultDto =
        runOperation("deleteExportedData") {
            logger.warn("Export-and-delete requested on local TSE to $outputPath - the delete is permanent!")
            // Checked on the server before the device sitting that exports and deletes: a writable
            // folder, the Admin sitting (423), and room for the archive (the read-only size query
            // writes no log entry).
            val archiveDir = File(outputPath)
            requireTseArchiveWritable(archiveDir)
            val adminPin = getAdminPin()
            val exportBytes =
                ExportSizeOperation(adminPin = NO_ADMIN_PIN, lastState = null)
                    .execute(lifecycleManager.getTseStatus())
                    .fullExportSizeBytes
            requireTseArchiveRoom(archiveDir, exportBytes)
            DeleteExportedDataOperation(adminPin = adminPin, outputPath = outputPath)
                .execute(lifecycleManager.getTseStatus())
        }

    // ============================================================================================
    // User Management Endpoints
    // ============================================================================================

    override suspend fun unblockUser(
        puk: String,
        userId: String?,
        newPin: String?,
    ): TseDto.AdminResultDto =
        runOperation("unblockUser") {
            val pin = getAdminPin()
            logger.info("Unblocking local TSE user: ${userId ?: "ADMIN"}")
            UserUnblockOperation(
                adminPin = pin,
                userId = userId ?: "ADMIN",
                puk = puk,
                newPin = newPin ?: pin,
            ).execute(lifecycleManager.getTseStatus())
        }

    override suspend fun changePin(
        currentPin: String,
        newPin: String,
        userId: String?,
    ): TseDto.AdminResultDto =
        runOperation("changePin") {
            val pin = getAdminPin()
            logger.info("Changing PIN for local TSE user: ${userId ?: "ADMIN"}")
            ChangePinOperation(
                adminPin = pin,
                userId = userId ?: "ADMIN",
                currentPin = currentPin,
                newPin = newPin,
            ).execute(lifecycleManager.getTseStatus())
        }

    // ============================================================================================
    // CTSS Endpoints
    // ============================================================================================

    override suspend fun enableCtss(): TseDto.AdminResultDto =
        runOperation("enableCtss") {
            logger.info("Enabling CTSS on local TSE")
            CtssEnableOperation(adminPin = getAdminPin()).execute(lifecycleManager.getTseStatus())
        }

    override suspend fun disableCtss(): TseDto.AdminResultDto =
        runOperation("disableCtss") {
            logger.info("Disabling CTSS on local TSE")
            CtssDisableOperation(adminPin = getAdminPin()).execute(lifecycleManager.getTseStatus())
        }

    // ============================================================================================
    // Firmware Endpoints
    // ============================================================================================

    override suspend fun checkFirmwareUpdate(): TseDto.FirmwareUpdateCheckResultDto =
        runOperation("checkFirmwareUpdate") {
            logger.info("Checking firmware update on local TSE")
            FirmwareUpdateCheckOperation(adminPin = NO_ADMIN_PIN)
                .execute(lifecycleManager.getTseStatus())
        }

    // ============================================================================================
    // Decommission Endpoints
    // ============================================================================================

    override suspend fun decommission(): TseDto.AdminResultDto =
        runOperation("decommission") {
            logger.warn("Decommission requested on local TSE - IRREVERSIBLE!")
            DecommissionOperation(adminPin = getAdminPin()).execute(lifecycleManager.getTseStatus())
        }

    // ============================================================================================
    // Firmware Apply Endpoints
    // ============================================================================================

    override suspend fun applyBundledFirmwareUpdate(): TseDto.FirmwareApplyResultDto =
        runOperation("applyBundledFirmwareUpdate") {
            logger.warn("Applying bundled firmware update on local TSE")
            FirmwareApplyBundledOperation(adminPin = getAdminPin())
                .execute(lifecycleManager.getTseStatus())
        }

    // ============================================================================================
    // CSP Export Endpoints
    // ============================================================================================

    override suspend fun disableExportIfCspTestFails(): TseDto.AdminResultDto =
        runOperation("disableExportIfCspTestFails") {
            logger.info("Disabling export if CSP test fails on local TSE")
            DisableExportIfCspTestFailsOperation(adminPin = getAdminPin())
                .execute(lifecycleManager.getTseStatus())
        }

    // ============================================================================================
    // Log Entry Browsing Endpoints
    // ============================================================================================

    override suspend fun browseEntries(request: TseDto.LogEntryBrowseRequestDto): TseDto.LogEntryBrowseResultDto =
        runOperation("browseEntries") {
            logger.debug("Browsing entries on local TSE: direction=${request.direction}")
            BrowseEntriesOperation(
                adminPin = NO_ADMIN_PIN,
                direction = request.direction,
                entryId = request.entryId,
                count = request.count,
            ).execute(lifecycleManager.getTseStatus())
        }

    // ============================================================================================
    // LCM Export Endpoints
    // ============================================================================================

    override suspend fun exportLcm(): TseDto.LcmExportResultDto =
        runOperation("exportLcm") {
            logger.info("Exporting LCM data on local TSE")
            LcmExportOperation(adminPin = NO_ADMIN_PIN).execute(lifecycleManager.getTseStatus())
        }

    // ============================================================================================
    // Server Management Endpoints
    // ============================================================================================

    /**
     * Restart the local TSE hardware connection.
     *
     * For LOCAL mode, this re-initializes the lifecycle manager instead of
     * restarting a remote server process.
     */
    override suspend fun restartServer(): TseDto.AdminResultDto =
        try {
            logger.info("Restarting local TSE hardware connection...")
            lifecycleManager.close()
            lifecycleManager.initialize()
            TseDto.AdminResultDto(
                success = true,
                message = "Local TSE hardware connection restarted successfully",
            )
        } catch (e: Exception) {
            logger.error("Local TSE restart failed", e)
            throw unexpected("TSE restart failed", e)
        }

    // ============================================================================================
    // Utility Methods
    // ============================================================================================

    /**
     * Refresh configuration — reloads from ShopConfigurationManager.
     *
     * For LOCAL mode, the config is always read fresh from ShopConfigurationManager
     * so this is essentially a no-op, but logs for consistency.
     */
    override suspend fun refreshConfig() {
        logger.info("Local TSE configuration refreshed (config always read fresh from ShopConfigurationManager)")
    }

    /**
     * Update the TSE operating mode on local hardware.
     *
     * @throws PosException on invalid mode or unexpected failure
     */
    override suspend fun setMode(
        mode: String,
        clientId: String,
    ): Boolean =
        try {
            logger.info("Setting local TSE mode to: $mode")
            val tseMode = TseMode.valueOf(mode)
            lifecycleManager.getTseStatus().setMode(tseMode)
            true
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid TSE mode: $mode")
            throw PosException(
                code = PosErrorCode.TSE_INVALID_PARAMETERS,
                httpStatus = HttpStatusCode.BadRequest,
                message = "Invalid TSE mode: $mode. Must be NORMAL, SIMULATION, or DISABLED",
            )
        } catch (e: Exception) {
            logger.error("Local TSE setMode failed", e)
            throw unexpected("TSE setMode failed", e)
        }

    /**
     * Check if TSE is initialized by querying hardware info directly.
     *
     * Swallows [PosException] from getInfo and returns false (matches the previous
     * `Result.Error -> false` semantics).
     */
    override suspend fun isInitialized(): Boolean =
        try {
            getInfo().initializationState == "WORM_INIT_INITIALIZED"
        } catch (e: PosException) {
            false
        }

    // ============================================================================================
    // Internal helpers
    // ============================================================================================

    /**
     * [runOperation] for a filtered export: on failure, removes the empty TAR the operation opened
     * and reports the device's "no data in this range" answer as 404 ([FilteredExportFailure]).
     *
     * The client filter is the caller's choice (F69): null exports **all clients** — pos-tse passes
     * the SDK's "all clients" value, `""` to the TSE 1.x hardware filter and `null` to the TSE 2.0
     * software filter — and a client id exports exactly that client. pos-tse never falls back to the
     * configured `TseStatus.getClientId()` any more; that fallback held only the maintenance/self-test
     * client's transactions (QA rt12). `/api/tse/verify` flags any archive missing a till's
     * transactions (`POS_TRANSACTION_MISSING_FROM_EXPORT`) — expected for a single-client export.
     */
    private suspend fun filteredExport(
        name: String,
        outputPath: String,
        range: String,
        clientId: String?,
        block: suspend () -> TseDto.ExportResultDto,
    ): TseDto.ExportResultDto {
        requireClientOnTse(clientId)
        val before = FilteredExportFailure.snapshot(outputPath)
        return try {
            runOperation(name) { distinctSecondExport(block) }
        } catch (e: PosException) {
            FilteredExportFailure.removeEmptyLeftovers(outputPath, before)
            throw FilteredExportFailure.noDataAsNotFound(e, range)
        }
    }

    /**
     * The pos-tse export operations name their TAR to the second (`tse_export_<yyyyMMdd_HHmmss>.tar`),
     * so two exports started in the same second wrote the same file and the second replaced the first
     * (RTSE-29). Exports run one at a time, and one that would start in the second the previous one
     * started in waits for the next second.
     */
    private suspend fun <T> distinctSecondExport(block: suspend () -> T): T =
        exportNaming.withLock {
            val now = System.currentTimeMillis()
            if (now / 1000 == lastExportStartMillis / 1000) delay(1000 - now % 1000)
            lastExportStartMillis = System.currentTimeMillis()
            block()
        }

    /**
     * A filtered export for a client the TSE does not list is refused before the device is asked: the
     * device answers it with a TAR holding only the info, certificate and system log entries, which an
     * auditor would read as that client having no transactions. When the list cannot be read (the
     * administration is locked) the export proceeds as before.
     */
    private suspend fun requireClientOnTse(clientId: String?) {
        if (clientId == null) return
        val listed = runCatching { listClients().clients }.getOrNull() ?: return
        if (clientId in listed) return
        throw PosException(
            PosErrorCode.TSE_TRANSACTION_NOT_FOUND,
            HttpStatusCode.NotFound,
            "The TSE does not list the client '$clientId'; no file was written",
        )
    }

    /**
     * Run a TSE operation block. The underlying [org.example.project.tse.operations]
     * throw [PosException] directly per the B1 cross-walk (post-Task-5); this helper
     * just lets PosException propagate and wraps unexpected exceptions as
     * `PosException(TSE_OPERATION_FAILED, …, cause = e)`.
     */
    private suspend fun <T> runOperation(
        name: String,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: PosException) {
            throw e
        } catch (e: Exception) {
            logger.error("Local TSE $name failed", e)
            throw unexpected("TSE $name failed", e)
        }

    private fun unexpected(
        msg: String,
        e: Throwable,
    ): PosException =
        PosException(
            code = PosErrorCode.TSE_OPERATION_FAILED,
            httpStatus = HttpStatusCode.InternalServerError,
            message = "$msg: ${e.message ?: e::class.simpleName}",
            cause = e,
        )
}

/** This health result carrying [expiry] (epoch seconds), unless it already has one or [expiry] is unknown. */
internal fun TseDto.HealthCheckResultDto.withCertificateExpiry(expiry: Long?): TseDto.HealthCheckResultDto =
    if (expiry == null || expiry <= 0L || certificateExpirationDate != 0L) this else copy(certificateExpirationDate = expiry)
