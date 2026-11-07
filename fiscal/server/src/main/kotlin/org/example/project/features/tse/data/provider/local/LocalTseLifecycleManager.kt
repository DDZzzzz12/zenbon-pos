/**
 * Manages the TSE hardware lifecycle when running in LOCAL connection mode.
 *
 * Owns the TSE hardware lifecycle inside the main POS server process. Handles TSE hardware detection,
 * initialization, maintenance task scheduling, and cleanup.
 *
 * Configuration is read directly from [ShopConfigurationManager] since we ARE
 * the main server (no HTTP config request needed).
 *
 * @since 1.0.0
 */
package org.example.project.features.tse.data.provider.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseHealthState
import org.example.project.features.tse.domain.model.TseInitResult
import org.example.project.features.tse.domain.provider.TseStatusBroadcaster
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.firmware.FirmwareUpdateCheckOperation
import org.example.project.tse.tasks.TseMaintenanceTask
import org.example.project.tse.utils.TseLocator
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * Lifecycle manager for local TSE hardware access.
 *
 * Manages the [TseStatus] instance and [TseMaintenanceTask] for LOCAL mode.
 */
class LocalTseLifecycleManager(
    // Injectable so the NORMAL branch is unit-testable. Production reads the persisted config and
    // probes the real mount point exactly as before.
    private val configSource: suspend () -> TseConfigDto.TseConfig = { ShopConfigurationManager.getTseConfig() },
    private val locate: () -> String? = { TseLocator.findAndValidateTse() },
    // Device seams past a successful locate; null drives the real TseStatus. Injectable so the
    // NORMAL bring-up is unit-testable without the JNI WormAPI.
    private val attachDevice: ((String) -> Boolean)? = null,
    private val readInitializationState: (() -> String)? = null,
    private val configureDevice: ((TseConfigDto.TseConfig, String?) -> Boolean)? = null,
    // The register the driver works for (production: TseDriverClient). The default answers "none
    // registered", which refuses a TSE 1.x self test rather than guessing a client.
    private val resolveClientId: suspend () -> String? = { null },
) {
    private val logger = LoggerFactory.getLogger(LocalTseLifecycleManager::class.java)

    private val tseStatus = TseStatus()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var maintenanceTask: TseMaintenanceTask? = null

    @Volatile
    private var lastConfiguredMode: TseMode? = null

    /**
     * The mode the operator configured, read fresh from the persisted config — the source of truth.
     * Not a snapshot of the last [initialize]: `restartConnection()` persists NORMAL without
     * re-initialising, and a snapshot would then let a still-SIMULATION TseStatus sign for a
     * configured-NORMAL shop, the very drift the signing guard exists to refuse.
     */
    suspend fun configuredMode(): TseMode = configSource().mode.also { lastConfiguredMode = it }

    /**
     * The configured mode last seen by [initialize] or [configuredMode], or null before either ran.
     * Only for non-suspending callers (`isConnected()`); the signing guard uses [configuredMode].
     */
    fun lastKnownConfiguredMode(): TseMode? = lastConfiguredMode

    /**
     * The registered client the driver runs its self tests for, resolved fresh on every call so a
     * terminal registered while the server runs is picked up. Null when no active terminal is
     * registered on the TSE — see [TseDriverClient] for why there is no fallback.
     */
    suspend fun signingClientId(): String? = resolveClientId()

    /**
     * Finds the TSE again after its mount point was lost, so one bad Vorgang does not disable
     * signing for the rest of the process.
     *
     * `TseStatus.handleHardwareError()` closes the `WormStore`, drops the connection and clears the
     * mount point. Nothing put it back: [initialize] runs at boot and on an operator action, so
     * after the first such error every signing attempt and every health check failed until restart,
     * while `TseSigningRetryService` skipped each cycle at *debug* level and the queue stalled in
     * silence (E2E-2026-09-20 #13). It is reached by ordinary events, not just real faults —
     * a `WORM_ERROR_CLIENT_NOT_REGISTERED`, which is a per-client configuration problem, is
     * classified as a hardware IO error and tears the whole connection down.
     *
     * Called from the health-check tick, so recovery costs at most one cycle. Deliberately cheap
     * and side-effect free when there is nothing to do: an attached TSE is never re-probed, because
     * re-locating validates by opening the device and a working one must not be closed and reopened
     * every minute.
     *
     * A device that answers is given the same driver configuration startup gives it
     * ([restoreDriverConfiguration]): the registered client and the stored TimeAdmin PIN. Startup
     * skips that step when it finds no device, and a TSE plugged in later — or re-plugged after a
     * power loss on the USB port — then reached the power-loss recovery without either (real
     * Swissbit TSE 1.x, 2026-10-10): its self test failed with "no client ID is configured" every
     * cycle, and had it got as far as the time update, the blank PIN would have cost a TimeAdmin
     * login attempt.
     *
     * @param currentMountPoint what `TseStatus` holds now; null means the connection was torn down.
     * @param configuredMode the shop's configured mode — only NORMAL has hardware to find.
     * @return true when a mount point was re-acquired, false when there was nothing to do or the
     *   TSE is genuinely still absent.
     */
    suspend fun reacquireIfLost(
        currentMountPoint: String?,
        configuredMode: TseMode?,
        attach: (String) -> Boolean = ::attachMountPoint,
    ): Boolean {
        if (configuredMode != TseMode.NORMAL) return false
        if (currentMountPoint != null) return false

        logger.warn("TSE mount point was lost — probing for the device again")
        val mountPoint = locate()
        if (mountPoint == null) {
            logger.info("TSE still not detected; signing keeps queueing for Nachsignieren")
            return false
        }
        val connected = attach(mountPoint)
        logger.info("TSE re-acquired at $mountPoint (connected=$connected)")
        if (connected) restoreDriverConfiguration()
        return true
    }

    /**
     * Gives the re-attached driver the client and TimeAdmin PIN startup would have given it
     * (`TseStatus.configureFromTseConfig`), without startup's full initialization sequence: the
     * self test and time update are the power-loss recovery's job, off this health-check thread.
     *
     * A blank stored PIN is not written — the driver keeps what it has rather than trading it for
     * a value that would fail the TimeAdmin login. With no registered terminal the client is left
     * alone and the recovery's self test refuses with an operator message ([LocalTseSelfTest]).
     */
    private suspend fun restoreDriverConfiguration() {
        val timeAdminPin = configSource().timeAdminPin
        if (timeAdminPin.isNotBlank()) tseStatus.setTimeAdminPin(timeAdminPin)

        val clientId = clientIdOrNullOnFailure()
        if (clientId == null) {
            logger.warn("TSE re-acquired, but no active terminal is registered on it — a TSE 1.x self test cannot run until one is")
            return
        }
        tseStatus.setClientId(clientId)
    }

    /**
     * [resolveClientId] for the bring-up paths, where a failed lookup must not abort the server
     * start or the health tick: it degrades to "no client", which the self test refuses with a
     * message rather than guessing one.
     */
    private suspend fun clientIdOrNullOnFailure(): String? =
        try {
            resolveClientId()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not look up the TSE client (registered terminal): ${e.message}")
            null
        }

    /**
     * Starts the scheduled maintenance (periodic self test and time sync) when nothing has started
     * it yet — the case of a server that started without its TSE, where [initialize] returned before
     * the configure step. Called only after a power-loss recovery *succeeded*: its time update has
     * just proven the stored TimeAdmin PIN, and an unproven PIN must not be retried every ten
     * minutes (PIN-lockout safety, as in [initialize]). A task that already exists is left alone.
     */
    fun ensureMaintenanceTaskRunning() {
        if (maintenanceTask != null) return
        if (getOrCreateMaintenanceTask().start()) {
            logger.info("TSE maintenance task started after recovering a TSE attached at runtime")
        }
    }

    /**
     * Opens the TSE for commissioning when nothing has opened it yet, and reports whether it answers.
     *
     * [initialize] opens the device only for a configured NORMAL mode. A fresh install is configured
     * DISABLED, so the device was never opened and setup found no WormStore to commission — on the
     * real Swissbit TSE it stayed UNINITIALIZED. Commissioning is the operator asking to run the TSE,
     * so setup opens it here regardless of the configured mode. Does not change the mode.
     *
     * @return true when the device is open and connected.
     */
    fun attachForCommissioning(attach: (String) -> Boolean = ::attachMountPoint): Boolean {
        if (tseStatus.hasWormStoreInstance()) return true
        val mountPoint = locate()
        if (mountPoint == null) {
            logger.warn("TSE setup: no TSE detected")
            return false
        }
        val connected = attach(mountPoint)
        logger.info("TSE setup: opened TSE at $mountPoint (connected=$connected)")
        return connected
    }

    /**
     * Opens a located mount point and reports whether the device answered.
     *
     * Split out so [reacquireIfLost] is unit-testable: `TseStatus.setMountPoint` opens the device
     * through the JNI `WormAPI`, which no test JVM has on its library path.
     */
    private fun attachMountPoint(mountPoint: String): Boolean {
        tseStatus.setMountPoint(mountPoint)
        return tseStatus.isConnected()
    }

    /**
     * Initialize the TSE for local access.
     *
     * Reads the configured [TseMode] first and only probes hardware when NORMAL is requested:
     *  - DISABLED: skip hardware entirely.
     *  - SIMULATION: skip hardware entirely; signing operations run against in-memory mocks
     *    (see [org.example.project.tse.operations.signing.BaseTseSignOperation.performSimulated]).
     *  - NORMAL: detect mount point + connect; on hardware failure, KEEP mode NORMAL,
     *    mark the device not-connected, and broadcast so transactions follow failureBehavior
     *    (QUEUE -> Nachsignieren). The mode is NOT auto-switched to DISABLED.
     */
    fun initialize(configure: Boolean = true): TseInitResult {
        logger.info("Initializing local TSE hardware access...")

        val config = runBlocking { configSource() }
        lastConfiguredMode = config.mode

        when (config.mode) {
            TseMode.DISABLED -> {
                tseStatus.setMode(TseMode.DISABLED)
                logger.info("Local TSE initialization skipped - mode: DISABLED")
                broadcastInitialState(connected = false, healthy = false, mode = "DISABLED")
                return TseInitResult.Disabled
            }
            TseMode.SIMULATION -> {
                tseStatus.setMode(TseMode.SIMULATION)
                logger.info("Local TSE initialization complete - mode: SIMULATION (no hardware required)")
                broadcastInitialState(connected = true, healthy = false, mode = "SIMULATION")
                return TseInitResult.Operational
            }
            TseMode.NORMAL -> {
                // Set the mode BEFORE any early return below. TseStatus defaults to SIMULATION
                // (pos-tse TseStatus.kt:99); every "not operational" return used to leave it there,
                // so BaseTseSignOperation dispatched to performSimulated and the shop issued
                // SIM-… signatures stored as SIGNED. NORMAL + not connected makes
                // BaseTseSignOperation throw TSE_HARDWARE_NOT_FOUND → failureBehavior (QUEUE).
                tseStatus.setMode(TseMode.NORMAL)
            }
        }

        // NORMAL mode: probe hardware
        val mountPoint = locate()
        if (mountPoint == null) {
            logger.warn("TSE hardware not detected - NORMAL mode stays NORMAL but is not operational (Nachsignieren will queue)")
            broadcastInitialState(connected = false, healthy = false, mode = "NORMAL")
            return TseInitResult.Unreachable("hardware not detected")
        }

        val connected = attachDevice?.invoke(mountPoint) ?: attachMountPoint(mountPoint)
        if (!connected) {
            logger.warn(
                "TSE hardware detected but connection failed - NORMAL mode stays NORMAL but is not operational (Nachsignieren will queue)",
            )
            broadcastInitialState(connected = false, healthy = false, mode = "NORMAL")
            return TseInitResult.Unreachable("connection failed")
        }

        try {
            config.validate()
        } catch (e: Exception) {
            logger.warn("TSE configuration not valid", e)
            return TseInitResult.InvalidConfig(e.message ?: "invalid configuration")
        }

        // No credential seed here: it is single-use and consumed at commissioning, which is
        // an operator-triggered act (POST /api/tse/setup), never part of an unattended start.
        if (configure) {
            // An UNINITIALIZED TSE has nothing to bring up: the startup sequence refuses it before
            // any login, and it used to come back as AuthFailed — "check the admin PIN" — on every
            // config save before setup, including the setup dialog's own pre-save (found on a real
            // Swissbit TSE). Answer it here, without touching the device's login at all.
            if (isUncommissioned()) {
                logger.info("TSE is not commissioned yet (UNINITIALIZED) - not configuring; run TSE setup to commission it")
                broadcastInitialState(connected = true, healthy = false, mode = config.mode.name)
                return TseInitResult.NotCommissioned
            }

            // The client id is resolved here, not left null. LOCAL mode never went via the
            // /api/internal/tse/config route, so nothing on this path had ever set it — and a
            // null one reaches the SDK's self test, which rejects it at the JNI boundary. Resolved
            // by the same TseDriverClient as re-acquiring and every self test, for every caller:
            // startup, "TSE neu starten" and a saved configuration.
            val clientId = runBlocking { clientIdOrNullOnFailure() }
            val configured =
                configureDevice?.invoke(config, clientId)
                    ?: tseStatus.configureFromTseConfig(
                        clientIdValue = clientId,
                        timeAdminPinValue = config.timeAdminPin,
                        modeValue = config.mode,
                    )

            if (!configured) {
                // Located + connected but admin/time-admin login/config failed: AUTH failure.
                // Do NOT auto-retry and do NOT start the maintenance task (it would re-attempt
                // login and risks PIN lockout). Surface distinctly so the UI can offer PUK unblock.
                val authFailure = tseStatus.getLastAuthFailure()
                logger.error(
                    "TSE configure failed (admin/time-admin login or init) - mode stays {} but NOT operational; " +
                        "not starting maintenance task (PIN-lockout safety). remainingRetries={}, blocked={}",
                    config.mode,
                    authFailure?.remainingRetries,
                    authFailure?.pinBlocked,
                )
                broadcastInitialState(connected = true, healthy = false, mode = config.mode.name)
                return TseInitResult.AuthFailed(
                    reason = "admin/time-admin login or initialization failed",
                    remainingRetries = authFailure?.remainingRetries,
                    locked = authFailure?.pinBlocked == true,
                )
            }

            if (tseStatus.getMode() == TseMode.NORMAL) {
                maintenanceTask = TseMaintenanceTask(tseStatus, coroutineScope)
                val started = maintenanceTask?.start() ?: false
                if (started) {
                    logger.info("TSE maintenance task started for LOCAL mode")
                } else {
                    logger.warn("TSE maintenance task did not start")
                }
            }
        }

        // One firmware check per start, and only once the device is actually operational.
        // AGD 2.8 makes informing the operator the manufacturer's duty, and the settings-tab
        // indicator alone reaches nobody who does not open that tab. Best-effort: a failed
        // check must never keep an otherwise healthy TSE from coming up.
        recordFirmwareAvailabilityForBroadcast()

        logger.info("Local TSE hardware initialization complete - mode: ${tseStatus.getMode()}")
        return TseInitResult.Operational
    }

    /**
     * Whether the attached device reports itself UNINITIALIZED.
     *
     * Read from the state `TseStatus.setMountPoint` cached when it opened the device, so this costs
     * no device I/O. Compared by name: the SDK enum is not on this module's compile classpath.
     */
    private fun isUncommissioned(): Boolean {
        val state = readInitializationState?.invoke() ?: tseStatus.getStatusInfo().initializationState
        return state == UNINITIALIZED_STATE
    }

    /**
     * Get the managed [TseStatus] instance.
     *
     * @return The TseStatus for direct hardware operations
     */
    fun getTseStatus(): TseStatus = tseStatus

    /**
     * Get or create the managed [TseMaintenanceTask].
     *
     * Returns the existing maintenance task if one is already running,
     * or creates a new one using the lifecycle manager's [coroutineScope].
     * The task is tracked and will be properly stopped on [close].
     *
     * @return The managed TseMaintenanceTask instance
     */
    fun getOrCreateMaintenanceTask(): TseMaintenanceTask =
        maintenanceTask ?: TseMaintenanceTask(tseStatus, coroutineScope).also {
            maintenanceTask = it
        }

    /**
     * Close the lifecycle manager and release all resources.
     *
     * Stops the maintenance task, disconnects hardware, and cancels the coroutine scope.
     */
    fun close() {
        logger.info("Closing LocalTseLifecycleManager...")
        maintenanceTask?.stop()
        tseStatus.close()
        coroutineScope.cancel()
        logger.info("LocalTseLifecycleManager closed")
    }

    private fun recordFirmwareAvailabilityForBroadcast() {
        if (tseStatus.getMode() != TseMode.NORMAL) return
        coroutineScope.launch {
            try {
                val result = FirmwareUpdateCheckOperation(adminPin = "").execute(tseStatus)
                TseStatusBroadcaster.recordFirmwareUpdateAvailable(result.bundledUpdateAvailable)
            } catch (e: Exception) {
                logger.warn("Startup firmware-update check failed: ${e.message}")
            }
        }
    }

    private fun broadcastInitialState(
        connected: Boolean,
        healthy: Boolean,
        mode: String,
    ) {
        coroutineScope.launch {
            try {
                val health =
                    if (!connected) {
                        TseHealthState.UNREACHABLE
                    } else if (healthy) {
                        TseHealthState.HEALTHY
                    } else {
                        TseHealthState.DEGRADED
                    }
                TseStatusBroadcaster.broadcastIfStateChanged(
                    connected = connected,
                    health = health,
                    mode = mode,
                )
            } catch (e: Exception) {
                logger.warn("Failed to broadcast initial TSE state ($mode): ${e.message}")
            }
        }
    }

    private companion object {
        /** `WormInitializationState.WORM_INIT_UNINITIALIZED.name`. */
        const val UNINITIALIZED_STATE = "WORM_INIT_UNINITIALIZED"
    }
}
