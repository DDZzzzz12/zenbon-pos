package org.example.project.tse.models

import com.secureflashcard.wormapi.WormInitializationState
import com.secureflashcard.wormapi.WormStore
import com.secureflashcard.wormapi.WormUserId
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.tasks.TseInitializationManager
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.Duration as JDuration
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Thread-safe TSE (Technical Security Element) status and configuration manager.
 *
 * This class manages the connection state, operating mode, and configuration of the
 * Swissbit TSE hardware. It enforces thread safety through a Kotlin coroutine Mutex,
 * ensuring that only one TSE command can execute at a time.
 *
 * Thread Safety Requirement (WormStore.java lines 104-112):
 * > "The Swissbit TSE hardware can not be operated from multiple threads or processes
 * > at the same time, as it allows only one command to be active at any given time and
 * > rejects all further commands. Thus, it is not allowed to use this library from
 * > multiple threads when talking to the same TSE."
 *
 * Operating Modes:
 * - NORMAL: Real TSE hardware operations with actual signatures
 * - SIMULATION: Mock operations for development/testing without hardware
 *
 * Connection States:
 * - CONNECTED: TSE hardware detected and accessible
 * - DISCONNECTED: No TSE hardware found
 * - ERROR: Hardware access error or initialization failure
 *
 * Usage Example:
 * ```kotlin
 * val tseStatus = TseStatus()
 *
 * // Connect to TSE hardware (creates and stores WormStore instance)
 * tseStatus.detectHardware()
 *
 * // Thread-safe command execution using stored WormStore instance
 * // Following WormAPI Example.java pattern: reuse the same instance
 * tseStatus.executeOnTseThread {
 *     val store = tseStatus.getWormStore()  // Get stored instance
 *     store.transaction_start(clientId, data, processType)
 *     // Instance remains open for next operation
 * }
 *
 * // Another operation reuses the same instance
 * tseStatus.executeOnTseThread {
 *     val store = tseStatus.getWormStore()  // Same instance
 *     store.transaction_finish(clientId, transactionNumber, data, processType)
 * }
 *
 * // Clean disconnect when done
 * tseStatus.disconnect()
 * ```
 *
 * @constructor Creates a new TseStatus instance with default configuration
 */
class TseStatus{
    private val logger = LoggerFactory.getLogger(TseStatus::class.java)

    /**
     * TSE connection state.
     */
    enum class ConnectionState {
        /** TSE hardware is connected and accessible */
        CONNECTED,

        /** TSE hardware is not detected */
        DISCONNECTED,

        /** TSE hardware error or initialization failure */
        ERROR
    }

    // Thread safety: Force all TSE operations onto one thread
    private val tseExecutor = Executors.newSingleThreadExecutor()


    // Connection and configuration state
    @Volatile
    private var connectionState: ConnectionState = ConnectionState.DISCONNECTED

    @Volatile
    private var mode: TseMode = TseMode.SIMULATION

    @Volatile
    private var mountPoint: String? = null

    @Volatile
    /**
     * The Kasse this driver signs for, supplied by configuration. No default.
     *
     * A TSE client is a Kasse under KassenSichV/DSFinV-K, and the SDK defines it as the
     * serial number of the system the TSE is plugged into — so it cannot be invented. The
     * previous "CLIENT-FOR-MAINTENANCE" default was registered on real devices because
     * setClientId() was never called outside tests, leaving live TSEs carrying a phantom
     * Kasse alongside the real one.
     */
    private var clientId: String? = null
    /**
     * The TimeAdmin PIN, supplied by configuration. No default.
     *
     * It used to default to "23456" — a value nobody chose, and one that did not even match
     * the server's own "12345" default, so the two disagreed about what an unconfigured
     * device's PIN was. Setup writes this into the TSE one-way, so a guess here is burned in
     * permanently; blank instead means an unset PIN is visibly unset and setup refuses.
     */
    @Volatile
    private var timeAdminPin: String = ""

    /**
     * The credential seed, supplied per setup call. No default.
     *
     * "SwissbitSwissbit" is the development-sample seed. Setup takes the seed as a request
     * parameter now, so a stored default only risks deriving credentials for the wrong device.
     */
    @Volatile
    private var credentialSeed: String = ""

    @Volatile
    private var isBusy: Boolean = false

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var initializationState: WormInitializationState = WormInitializationState.WORM_INIT_UNINITIALIZED

    @Volatile
    private var lastAuthFailure: org.example.project.core.domain.dto.tse.TseDto.TseAuthFailureInfo? = null

    /** Last admin/time-admin login failure detail (remaining attempts / blocked), or null. */
    fun getLastAuthFailure(): org.example.project.core.domain.dto.tse.TseDto.TseAuthFailureInfo? = lastAuthFailure

    /** Record a login auth failure so the server can surface real attempt count / locked state. */
    fun recordAuthFailure(info: org.example.project.core.domain.dto.tse.TseDto.TseAuthFailureInfo) {
        lastAuthFailure = info
    }

    /** Clear any recorded auth failure (called at the start of a configure attempt). */
    fun clearAuthFailure() {
        lastAuthFailure = null
    }

    /**
     * Singleton WormStore instance.
     *
     * Following the recommended pattern from WormAPI Example.java (line 171):
     * - Created once during hardware detection/connection
     * - Reused for all TSE operations
     * - Closed only during disconnect
     *
     * Thread Safety: Access is protected by tseMutex via executeOnTseThread
     */
    @Volatile
    private var wormStoreInstance: WormStore? = null

    /**
     * Whether the attached device is a TSE 2.0, captured once in [detectHardware].
     *
     * Cached rather than queried per call: on TSE 2.0 every authenticated command resets
     * the 40-minute idle-logout timer, so probing `info()` to answer this would change the
     * very behaviour callers branch on. Defaults to false so nothing takes a v2-only path
     * before a device is known, and is cleared on disconnect so swapping a TSE 2.0 for a
     * TSE 1.x cannot leave v2 paths active.
     */
    @Volatile
    private var isTseV2Device: Boolean = false

    /** True when the connected device is a TSE 2.0. See [isTseV2Device]. */
    fun isTseV2(): Boolean = isTseV2Device

    // ============================================================================================
    // Maintenance Flag
    // ============================================================================================

    private val maintenance = MaintenanceState()

    /** @return true if any tracked maintenance op is currently in flight. */
    fun isInMaintenance(): Boolean = maintenance.isActive()

    /** @return the names of all in-flight maintenance ops (diagnostic / broadcast). */
    fun currentMaintenanceOps(): Set<String> = maintenance.activeOps()

    /**
     * Mark a block of work as "TSE maintenance in progress".
     *
     * Counter-based — overlapping ops compose. The block runs synchronously; the flag
     * is set before [block] runs and cleared in `finally`, even on exception.
     *
     * A watchdog auto-clears the slot if [block] exceeds [maxDuration] and logs ERROR,
     * preventing a stuck slot from permanently masking real hardware state.
     *
     * @param opName short label used in logs and the broadcast `maintenanceOps` set,
     *               e.g. "scheduled-selftest", "scheduled-timesync", "manual-selftest",
     *               "manual-maintenance", "initialization", "reconnect".
     */
    fun <T> withMaintenance(
        opName: String,
        maxDuration: Duration = defaultMaxDuration(opName),
        block: () -> T,
    ): T {
        val id = maintenance.enter(opName, maxDuration)
        try {
            return block()
        } finally {
            maintenance.exit(id)
        }
    }

    /**
     * Suspend-aware variant of [withMaintenance]. Same semantics; accepts a suspend block.
     */
    suspend fun <T> withMaintenanceSuspend(
        opName: String,
        maxDuration: Duration = defaultMaxDuration(opName),
        block: suspend () -> T,
    ): T {
        val id = maintenance.enter(opName, maxDuration)
        try {
            return block()
        } finally {
            maintenance.exit(id)
        }
    }

    private fun defaultMaxDuration(opName: String): Duration = when (opName) {
        "scheduled-selftest", "manual-selftest"  -> 90.seconds
        "scheduled-timesync"                     -> 30.seconds
        "initialization", "reconnect"            -> 5.minutes
        "manual-maintenance"                     -> 3.minutes  // outer wrap; inner ops have own timeouts
        else                                     -> 2.minutes
    }

    // ============================================================================================
    // Public Read-Only Accessors
    // ============================================================================================

    /**
     * Check if TSE hardware is currently connected.
     *
     * @return true if connection state is CONNECTED
     */
    fun isConnected(): Boolean = connectionState == ConnectionState.CONNECTED

    /**
     * Get the current operating mode.
     *
     * @return Current TseMode (NORMAL or SIMULATION)
     */
    fun getMode(): TseMode = mode

    /**
     * Get the TSE hardware mount point path.
     *
     * @return Mount point path (e.g., "/media/tse")
     */
    fun getMountPoint(): String? = mountPoint

    /**
     * Get the client identifier for TSE operations.
     *
     * @return Client ID string
     */
    fun getClientId(): String? = clientId

    /**
     * Get the time admin PIN for authentication.
     *
     * Used for TimeAdmin login which can only perform time updates.
     * Required for `tse_setup()` call.
     *
     * @return Time Admin PIN string
     */
    fun getTimeAdminPin(): String = timeAdminPin

    /**
     * Get the credential seed for TSE initialization.
     *
     * The credential seed is a hardware-specific secret provided by Swissbit.
     * For development samples, it's "SwissbitSwissbit".
     * For production hardware, it must come from Swissbit documentation
     * or the hardware packaging.
     *
     * Required for:
     * - `user_deriveInitialCredentials()` - to get initial PIN/PUK values
     * - `tse_setup()` - to initialize an UNINITIALIZED TSE
     *
     * @return Credential seed string
     */
    fun getCredentialSeed(): String = credentialSeed

    /**
     * Check if TSE is currently processing a command.
     *
     * @return true if a command is currently executing (mutex locked)
     */
    fun isBusy(): Boolean = isBusy

    /**
     * Get the last error message.
     *
     * @return Error message string or null if no error
     */
    fun getLastError(): String? = lastError

    /**
     * Get the TSE initialization state.
     *
     * @return Initialization state string (e.g., "INITIALIZED", "UNINITIALIZED")
     */
    fun getInitializationState(): WormInitializationState = initializationState

    // ============================================================================================
    // Configuration Setters
    // ============================================================================================

    /**
     * Set the operating mode.
     *
     * @param newMode The new operating mode (NORMAL or SIMULATION)
     */
    fun setMode(newMode: TseMode) {
        mode = newMode
        logger.info("TSE mode changed to: $newMode")
    }

    /**
     * Set the TSE mount point path.
     *
     * @param path The mount point path
     */
    fun setMountPoint(path: String?) {
        if (path != null) {
            mountPoint = path
            logger.info("TSE mount point changed to: $path")
            detectHardware()
        }
    }

    /**
     * Set the client identifier.
     *
     * @param id The client ID
     */
    fun setClientId(id: String) {
        clientId = id
        logger.info("TSE client ID changed to: $id")
    }

    /**
     * Set the time admin PIN.
     *
     * @param pin The time admin PIN (5-6 digit numeric string)
     */
    fun setTimeAdminPin(pin: String) {
        timeAdminPin = pin
        logger.info("TSE time admin PIN updated")
    }

    /**
     * Set the credential seed for TSE initialization.
     *
     * The credential seed is a hardware-specific secret provided by Swissbit.
     * Only needs to be set for production hardware; development samples use
     * the default value "SwissbitSwissbit".
     *
     * @param seed The credential seed string
     */
    fun setCredentialSeed(seed: String) {
        credentialSeed = seed
        logger.info("TSE credential seed updated")
    }

    fun setInitializationState(initState: WormInitializationState) {
        initializationState = initState
    }

    /**
     * Configure TSE from TseConfig received from main server.
     *
     * Updates all configuration values at once from the main server's
     * TseConfig data. This is called during startup when TSE server
     * receives configuration from the main server.
     *
     * Note: Maintenance task should be started by the caller (TseApplication)
     * after this method returns true, as it requires a CoroutineScope.
     *
     * @return true if configuration and initialization succeeded, false otherwise
     */
    fun configureFromTseConfig(
        clientIdValue: String?,
        timeAdminPinValue: String,
        modeValue: TseMode,
    ): Boolean = configureAndRun(clientIdValue, timeAdminPinValue, modeValue) { initManager ->
        initManager.initialize(clientIdValue)
    }

    /**
     * Commission an uninitialized TSE with operator-supplied credentials, then bring it up.
     *
     * The credentials arrive as arguments and are never persisted — see
     * [TseInitializationManager.setUp].
     */
    fun commissionFromOperator(
        clientIdValue: String?,
        adminPin: String,
        adminPuk: String,
        timeAdminPinValue: String,
        credentialSeed: String,
        modeValue: TseMode,
    ): Boolean = configureAndRun(clientIdValue, timeAdminPinValue, modeValue) { initManager ->
        initManager.setUp(adminPin, adminPuk, credentialSeed, clientIdValue)
    }

    private fun configureAndRun(
        clientIdValue: String?,
        timeAdminPinValue: String,
        modeValue: TseMode,
        run: (TseInitializationManager) -> Boolean,
    ): Boolean {
        logger.info("Configuring TSE from main server configuration...")
        clearAuthFailure()
        // Record the client here, not only inside the init manager. The manager kept the
        // resolved id to itself, so TseStatus stayed null and the sixteen other readers of
        // getClientId() — the exports, the simulated signing path, the maintenance self test —
        // saw nothing. Only the dedicated-server deployment ever set it, via
        // ApplyConfigFromMainServer; the in-process LOCAL mode never went through that path.
        clientIdValue?.let { setClientId(it) }
        setTimeAdminPin(timeAdminPinValue)
        setMode(modeValue)

        // Run initialization sequence (handles both UNINITIALIZED and INITIALIZED)
        if (modeValue == TseMode.NORMAL) {
            logger.info("Running TSE initialization sequence...")
            val initialized = run(TseInitializationManager(this))

            if (!initialized) {
                // Stay NORMAL-but-not-operational. Do NOT fall into SIMULATION: that mocks
                // signatures (BaseTseSignOperation routes SIMULATION to performSimulated), so
                // a shop with an uncommissioned NORMAL TSE would silently issue fake-signed
                // receipts while believing it trades normally. NORMAL-not-operational makes
                // the real sign path queue for Nachsignieren instead, and keeps the status
                // from advertising SIMULATION as an automatic second option. SIMULATION is a
                // mode an operator chooses, never one the system falls into.
                logger.error("✗ TSE initialization sequence failed — staying NORMAL but NOT operational")
                logger.error("  Signing will queue for Nachsignieren until the TSE is commissioned.")
                return false
            }
            logger.info("  - Mode: $mode")
            logger.info("✓ Step 3: TSE initialization sequence completed")

            logger.info("═══════════════════════════════════════════════════════════════════════════")
            logger.info("        TSE INITIALIZATION COMPLETE - NORMAL MODE ACTIVE                  ")
            logger.info("═══════════════════════════════════════════════════════════════════════════")
        } else {
            logger.info("  - Mode: $mode")
            logger.info("═══════════════════════════════════════════════════════════════════════════")
            logger.info("        TSE INITIALIZATION COMPLETE - SIMULATION MODE ACTIVE              ")
            logger.info("═══════════════════════════════════════════════════════════════════════════")
        }

        return true
    }


    // ============================================================================================
    // Thread-Safe Command Execution
    // ============================================================================================

    /**
     * Execute TSE operation with exclusive lock.
     *
     * This method ensures thread safety by acquiring a mutex before executing the operation.
     * Only one thread can execute a TSE operation at a time. Concurrent requests will be
     * queued in FIFO order and executed sequentially.
     *
     * The mutex is automatically released when the operation completes or throws an exception.
     *
     * Usage Example:
     * ```kotlin
     * val result = tseStatus.executeOnTseThread {
     *     WormStore(tseStatus.getMountPoint()).use { store ->
     *         store.transaction_start(clientId, data, processType)
     *     }
     * }
     * ```
     *
     * Thread Safety Guarantee:
     * - Only one operation executes at a time across all threads
     * - Operations execute in the order they acquire the mutex
     * - Mutex releases automatically on completion or exception
     *
     * @param T The return type of the operation
     * @param operation The TSE operation to execute (lambda with TSE API calls)
     * @return The result of the operation
     * @throws Exception Propagates any exception from the operation after releasing mutex
     */
    /**
     * Run the device self test, choosing the overload the SDK actually wants.
     *
     * The SDK's `tse_runSelfTest(String)` goes through a SWIG wrapper that rejects a null
     * jstring before the SDK sees it — a NullPointerException, not a WormException, so the
     * `catch (WormException)` around these calls does not catch it. TSE 2.0 ignores the
     * client entirely and ships a no-arg overload for exactly that case; TSE 1.x genuinely
     * needs a registered client and cannot proceed without one.
     *
     * @param clientId the Kasse to run for, or null when none is configured
     * @throws IllegalStateException on TSE 1.x with no client ID — diagnosable, unlike an NPE
     */
    fun runSelfTestOnDevice(clientId: String? = getClientId()) {
        val store = getWormStore()
        when {
            clientId != null -> store.tse_runSelfTest(clientId)
            isTseV2() -> store.tse_runSelfTest()
            else -> throw IllegalStateException(
                "Cannot run the TSE self test: no client ID is configured, and TSE 1.x " +
                    "requires a registered client. Commission this register against the TSE.",
            )
        }
    }

    fun <T> executeOnTseThread(operation:  () -> T): T {
        return try {
            tseExecutor.submit(Callable { operation() }).get()
        } catch (e: ExecutionException) {
            // Future.get() wraps whatever the task threw. Rethrowing the cause keeps the
            // exception the caller is actually written against: BaseTseOperation.execute
            // catches WormException to map it per the B1 cross-walk, and that catch could
            // never fire for a locked operation while every failure arrived as an
            // ExecutionException. PosExceptions raised inside an operation were reaching
            // the operator the same way — wrapped, and so unmapped by StatusPages.
            throw e.cause ?: e
        }
    }

    // ============================================================================================
    // Status Information
    // ============================================================================================

    /**
     * Get comprehensive TSE status information as DTO.
     *
     * @return TseDto.StatusInfoDto containing current connection state, mode, configuration
     */
    fun getStatusInfo(): TseDto.StatusInfoDto {
        // WormStore.version() and logTimeFormat() are static — no connection needed
        val sdkVersion = try { WormStore.version() } catch (_: Exception) { null }
        val logTimeFormat = try { WormStore.logTimeFormat() } catch (_: Exception) { null }

        return TseDto.StatusInfoDto(
            connected = isConnected(),
            mode = mode.name,
            mountPoint = mountPoint,
            busy = isBusy,
            initializationState = initializationState.name,
            lastError = lastError,
            sdkVersion = sdkVersion,
            logTimeFormat = logTimeFormat
        )
    }

    // ============================================================================================
    // WormStore Instance Access
    // ============================================================================================

    /**
     * Get the WormStore instance for TSE operations.
     *
     * This method provides access to the singleton WormStore instance that should be
     * reused for all TSE commands, following the pattern from Example.java.
     *
     * Thread Safety: This method should be called within executeOnTseThread {} to ensure
     * thread-safe access to the WormStore instance.
     *
     * Usage Example:
     * ```kotlin
     * tseStatus.executeOnTseThread {
     *     val store = tseStatus.getWormStore()
     *     store.transaction_start(clientId, data, processType)
     * }
     * ```
     *
     * @return The WormStore instance
     * @throws IllegalStateException if not connected or instance is null
     */
    fun getWormStore(): WormStore {
        val instance = wormStoreInstance
            ?: throw IllegalStateException(
                "TSE not connected. Call detectHardware() first to establish connection."
            )

        if (connectionState != ConnectionState.CONNECTED) {
            throw IllegalStateException(
                "TSE connection state is $connectionState. Expected CONNECTED."
            )
        }

        return instance
    }

    /**
     * Check if WormStore instance is available and ready for operations.
     *
     * @return true if WormStore instance exists and connection state is CONNECTED
     */
    fun hasWormStoreInstance(): Boolean {
        return wormStoreInstance != null && connectionState == ConnectionState.CONNECTED
    }

    // ============================================================================================
    // Hardware Detection
    // ============================================================================================

    /**
     * Detect and connect to TSE hardware.
     *
     * Attempts to open a connection to the TSE hardware at the configured mount point.
     * If successful, retrieves initialization state and updates connection status.
     *
     * Following WormAPI Example.java pattern (line 171):
     * - Creates WormStore instance and stores it for reuse
     * - Does NOT close the instance (kept alive for subsequent operations)
     * - Instance will be closed only during disconnect()
     *
     * Keep-Alive Configuration (WormStore.java lines 452-472):
     * - MUST be called immediately after WormStore creation
     * - BEFORE any other TSE operations (including runSelfTest)
     * - SDK handles automatic keep-alive in background
     * - Prevents OS from powering down TSE to save battery
     *
     * This operation is thread-safe and uses the exclusive lock mechanism.
     *
     * Detection Process:
     * 1. Acquire exclusive lock
     * 2. Close existing WormStore instance if present
     * 3. Create new WormStore instance with mount point
     * 4. IMMEDIATELY configure keep-alive (before any other operation)
     * 5. Retrieve TSE information (tests connection)
     * 6. Store instance for reuse
     * 7. Update connection state and initialization state
     * 8. Release lock
     *
     * @param enableKeepAlive Enable automatic keep-alive (default: true)
     * @param keepAliveInterval Keep-alive interval in seconds (default: 30, max: 3600)
     * @return true if TSE hardware detected and connected successfully, false otherwise
     */
    fun detectHardware(
        enableKeepAlive: Boolean = true,
        keepAliveInterval: Int = 30
    ): Boolean = executeOnTseThread {
        try {
            logger.info("Detecting TSE hardware at mount point: $mountPoint")

            // Close existing instance if present
            wormStoreInstance?.close()
            wormStoreInstance = null

            // Create new WormStore instance
            val store = WormStore(mountPoint)

            // CRITICAL: Configure keep-alive IMMEDIATELY after creation
            // BEFORE any other TSE operation (WormStore.java lines 452-472)
            if (enableKeepAlive) {
                // Validate interval (min: 1, max: 3600)
                val validInterval = keepAliveInterval.coerceIn(1, 3600)
                store.keepAliveConfigure(validInterval)
                logger.info("✓ Keep-alive configured: ${validInterval}s interval (SDK auto-manages)")
            } else {
                logger.info("Keep-alive disabled (not recommended for production)")
            }

            // Now we can safely use the WormStore for other operations
            // Test connection by retrieving info
            val info = store.info()
            info.use { info ->
                // Update initialization state
                initializationState = info.initializationState()
                // Read the generation once, here: see isTseV2Device for why not per call.
                isTseV2Device = info.isTSEv2()
            }

            // Store the instance for reuse (following Example.java pattern)
            wormStoreInstance = store
            connectionState = ConnectionState.CONNECTED
            lastError = null

            logger.info(
                "TSE hardware detected successfully. " +
                        "WormStore instance created and stored for reuse. " +
                        "Initialization state: $initializationState"
            )
            true
        } catch (e: Exception) {
            // Clean up on failure
            wormStoreInstance?.close()
            wormStoreInstance = null
            connectionState = ConnectionState.DISCONNECTED
            lastError = e.message
            logger.warn("Failed to detect TSE hardware: ${e.message}")
            false
        }
    }

    /**
     * Reconnect to TSE hardware.
     *
     * This is a convenience method that disconnects and then re-detects the hardware.
     * Useful for:
     * - Recovering from connection errors
     * - Refreshing the WormStore instance
     * - Re-establishing connection after hardware was removed and reinserted
     *
     * @return true if reconnection successful, false otherwise
     */
    fun reconnect(): Boolean = withMaintenance("reconnect") {
        logger.info("Reconnecting to TSE hardware...")
        disconnect()
        detectHardware()
    }

    /**
     * Disconnect from TSE hardware and reset state.
     *
     * Following WormAPI Example.java pattern:
     * - Disables keep-alive (stops SDK automatic commands)
     * - Properly closes the WormStore instance (releases hardware resources)
     * - Resets connection state and initialization state
     * - Clears error state
     *
     * This operation should be called when:
     * - Application is shutting down
     * - Switching to a different TSE device
     * - Recovering from a hardware error
     *
     * Thread Safety: This method should ideally be called within executeOnTseThread {}
     * to ensure no TSE operations are in progress during disconnection.
     */
    fun disconnect() = executeOnTseThread {
        try {
            // Step 1: Disable keep-alive (stops SDK automatic commands)
            wormStoreInstance?.keepAliveDisable()
            wormStoreInstance?.user_logout(WormUserId.WORM_USER_ADMIN)
            logger.info("Keep-alive disabled")
        } catch (e: Exception) {
            logger.debug("Keep-alive disable warning: ${e.message}")
            // Not critical - continue with close
        }

        try {
            // Step 2: Close WormStore instance if present
            wormStoreInstance?.close()
            logger.info("WormStore instance closed successfully")
        } catch (e: Exception) {
            logger.warn("Error closing WormStore instance: ${e.message}", e)
        } finally {
            // Step 3: Reset state regardless of close success
            wormStoreInstance = null
            connectionState = ConnectionState.DISCONNECTED
            initializationState = WormInitializationState.WORM_INIT_UNINITIALIZED
            isTseV2Device = false
            lastError = null
            logger.info("TSE hardware disconnected, state reset")
        }
    }

    /**
     * Handle a hardware IO error by resetting TSE state without acquiring the lock.
     *
     * This method is intended to be called AFTER [executeOnTseThread] has released
     * the mutex (i.e., when a [com.secureflashcard.wormapi.WormException] propagates
     * out of an operation). It safely closes the broken WormStore instance and
     * resets connection state so the TSE can be re-detected on the next operation.
     *
     * Unlike [disconnect], this method:
     * - Does NOT acquire the mutex (safe to call after lock release)
     * - Does NOT attempt keep-alive disable or user logout (hardware is broken)
     * - Resets mountPoint to null (forces re-detection via [TseLocator])
     *
     * @param cause The exception that triggered the hardware error
     */
    fun handleHardwareError(cause: Throwable? = null) {
        logger.warn("Handling TSE hardware error: ${cause?.message}")
        try {
            wormStoreInstance?.close()
        } catch (e: Exception) {
            logger.debug("Error closing WormStore during hardware error handling: ${e.message}")
        }
        wormStoreInstance = null
        connectionState = ConnectionState.DISCONNECTED
        mountPoint = null
        lastError = cause?.message
        logger.info("TSE state reset after hardware error (mountPoint cleared, connection state: DISCONNECTED)")
    }

    /**
     * Synchronous close method for Spring bean lifecycle.
     *
     * This method wraps the suspend [disconnect] function using runBlocking,
     * allowing Spring to call it as a destroyMethod on bean destruction.
     *
     * Usage in Spring configuration:
     * ```kotlin
     * @Bean(destroyMethod = "close")
     * fun tseStatus(): TseStatus
     * ```
     */
    fun close() {
        logger.info("Closing TseStatus (Spring bean lifecycle)...")
        disconnect()
    }
}

private class MaintenanceState {
    private val counter = AtomicInteger(0)
    private val active = ConcurrentHashMap<Long, ActiveOp>()
    private val idSeq = AtomicLong(0)
    private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "tse-maintenance-watchdog").apply { isDaemon = true }
    }
    private val logger = org.slf4j.LoggerFactory.getLogger(MaintenanceState::class.java)

    private data class ActiveOp(
        val name: String,
        val startedAt: Instant,
        val timeoutTask: ScheduledFuture<*>,
    )

    fun isActive(): Boolean = counter.get() > 0
    fun activeOps(): Set<String> = active.values.map { it.name }.toSet()

    fun enter(name: String, maxDuration: Duration): Long {
        val id = idSeq.incrementAndGet()
        val task = watchdog.schedule({
            // Atomic remove: if normal exit already cleared the slot, do nothing.
            if (active.remove(id) != null) {
                val left = counter.decrementAndGet()
                logger.error(
                    "TSE maintenance op '{}' (id={}) exceeded {}s — force-clearing slot. " +
                        "activeCount={}. Investigate the call site.",
                    name, id, maxDuration.inWholeSeconds, left
                )
            }
        }, maxDuration.inWholeMilliseconds, TimeUnit.MILLISECONDS)

        active[id] = ActiveOp(name, Instant.now(), task)
        val now = counter.incrementAndGet()
        logger.info("TSE maintenance ENTER op='{}' id={} activeCount={}", name, id, now)
        return id
    }

    fun exit(id: Long) {
        val op = active.remove(id) ?: return  // watchdog already cleared it
        op.timeoutTask.cancel(false)
        val left = counter.decrementAndGet()
        val durMs = JDuration.between(op.startedAt, Instant.now()).toMillis()
        logger.info(
            "TSE maintenance EXIT  op='{}' id={} durationMs={} activeCount={}",
            op.name, id, durMs, left
        )
    }
}
