package org.example.project.tse.virtualworm

import com.secureflashcard.wormapi.WormAuthenticationFailedException
import com.secureflashcard.wormapi.WormEntryType
import com.secureflashcard.wormapi.WormError
import com.secureflashcard.wormapi.WormException
import com.secureflashcard.wormapi.WormExportTarIncrementalResponse
import com.secureflashcard.wormapi.WormFirmwareUpdateCheckResponse
import com.secureflashcard.wormapi.WormFlashHealthSummary
import com.secureflashcard.wormapi.WormInitialCredentials
import com.secureflashcard.wormapi.WormInitializationState
import com.secureflashcard.wormapi.WormTransactionResponse
import com.secureflashcard.wormapi.WormTseFirmwareUpdate
import com.secureflashcard.wormapi.WormUserId
import java.security.MessageDigest

/**
 * In-memory simulation of a Swissbit TSE (WORM device) backing the virtual WormAPI
 * test double. One instance per mount point, created by [VirtualWormRegistry].
 *
 * The state machine follows the real device lifecycle as exercised by
 * `TseInitializationManager` and the operations layer:
 *
 * 1. Fresh device: UNINITIALIZED, factory credentials derivable from the credential
 *    seed (`"SwissbitSwissbit"` yields the Swissbit dev-sample values: admin PIN 12345,
 *    PUK 123456, time-admin PIN 12345 — matching `MockWormStoreFactory.Defaults`).
 * 2. Self-test: no login required, valid for 25h (WORM spec interval).
 * 3. PIN/PUK changes tracked via hasChanged* flags; `tse_initialize` requires all
 *    credentials changed (WRONG_STATE_NEEDS_PUK_CHANGE / _PIN_CHANGE otherwise) —
 *    like real hardware onboarding.
 * 4. Admin login: 3 attempts, then PIN blocked (AUTHENTICATION_FAILED with decreasing
 *    remainingRetries, then AUTHENTICATION_PIN_BLOCKED); `user_unblock` with the PUK
 *    recovers.
 * 5. Transactions require INITIALIZED + valid self-test + valid time + registered
 *    client, and produce monotonically increasing transaction/signature counters with
 *    deterministic fake signatures plus an append-only log-entry list for exports.
 *
 * Known divergences from real hardware (kept simple deliberately): signatures are
 * SHA-256-derived placeholders (not ECDSA), TAR exports contain a single synthetic
 * log file (not TR-03153 TLV data), `tse_updateTime` does not require an active CTSS
 * interface, and the entry list is capped at [MAX_ENTRIES] to bound test memory.
 *
 * ## TSE 2.0 mode
 *
 * [Generation.TSE_V2] additionally models: single-session login with a 40-minute idle
 * logout; the Logger user and its automatic login for transactions (including the
 * eviction of any Admin or TimeAdmin session that causes); per-user PUKs plus a Logger
 * PIN; initialization and set-time preconditions on client management; a self test that
 * requires initialization and bars TimeAdmin; TimeAdmin barred from exports and entry
 * reads; hardware-side filtered exports always failing; CTSS and CSP toggles as no-ops;
 * Admin plus a valid time required to delete stored data; a ~1 MB store-full reserve;
 * and rejection of `/`, `:` and `?` in client IDs.
 *
 * Deliberately **not** modelled in v2 mode: the SDK autopilot and `tse_startup`. Production
 * code does not use them (design spec §5.0) — both require the ERS to hold the Admin PIN,
 * which the Swissbit guidance manual forbids, so offering them here would invite exactly
 * the usage the design avoids.
 *
 * Where the double is deliberately *less* capable than it could be: on [Generation.TSE_V1]
 * it reports `loggedInUser` as `WORM_USER_UNAUTHENTICATED` and the auto-logout timeout as
 * `0xffff`, regardless of the true session — because that is what real v1 hardware does.
 * Internal authorization still tracks the real session; only the reported values are blanked.
 * Making the double more truthful than the device would let a test pass here and be wrong
 * about hardware.
 *
 * Also still v1-shaped in v2 mode: log messages remain the synthetic form above rather
 * than real TR-03153 **format version 3** data. Parsing that format is a separate piece of
 * work and will need real fixtures — until then, do not use this double to validate
 * log-message parsing or anything derived from it, including filtered-export contents.
 */
class VirtualWormDevice internal constructor(
    private val mountPoint: String,
    private val generation: Generation = Generation.TSE_V1,
) {

    /**
     * Which TSE hardware generation this device emulates.
     *
     * TSE 2.0 was certified under a new BSI Technical Guideline and changes device
     * behavior substantially: one user logged in at a time, idle auto-logout, a
     * Logger user for transactions, per-user PUKs, no filtered exports, and no CTSS
     * interface. [TSE_V1] stays the default so the existing suite keeps its meaning
     * as a regression guard for hardware already in the field.
     */
    enum class Generation { TSE_V1, TSE_V2 }

    private val isV2: Boolean get() = generation == Generation.TSE_V2

    class LogEntry internal constructor(
        val id: Long,
        val type: WormEntryType,
        val logMessage: ByteArray,
        val processData: ByteArray,
        val transactionNumber: Long,
        val logTime: Long,
        val clientId: String,
    )

    class Snapshot internal constructor(
        val customizationIdentifier: ByteArray,
        val isDevelopmentFirmware: Boolean,
        val capacity: Long,
        val size: Long,
        val hasValidTime: Boolean,
        val hasPassedSelfTest: Boolean,
        val isCtssInterfaceActive: Boolean,
        val isExportEnabledIfCspTestFails: Boolean,
        val initializationState: WormInitializationState,
        val isDataImportInProgress: Boolean,
        val hasChangedPuk: Boolean,
        val hasChangedAdminPin: Boolean,
        val hasChangedTimeAdminPin: Boolean,
        val timeUntilNextSelfTest: Long,
        val startedTransactions: Long,
        val maxStartedTransactions: Long,
        val createdSignatures: Long,
        val maxSignatures: Long,
        val remainingSignatures: Long,
        val maxTimeSynchronizationDelay: Long,
        val maxUpdateDelay: Long,
        val tsePublicKey: ByteArray,
        val timeUntilNextTimeSynchronization: Long,
        val tseSerialNumber: ByteArray,
        val tseDescription: String,
        val registeredClients: Long,
        val maxRegisteredClients: Long,
        val certificateExpirationDate: Long,
        val tarExportSizeInSectors: Long,
        val tarExportSize: Long,
        val hardwareVersion: Long,
        val softwareVersion: Long,
        val isTSEv2: Boolean,
        val loggedInUser: WormUserId,
        val loggedInUserAutoLogOutTimeout: Int,
        val hasChangedAdminPuk: Boolean,
        val hasChangedTimeAdminPuk: Boolean,
        val hasChangedLoggerPuk: Boolean,
        val hasChangedLoggerPin: Boolean,
        val formFactor: String,
    )

    private class UserAuth(var pin: ByteArray) {
        var remainingRetries: Int = MAX_LOGIN_RETRIES
        var blocked: Boolean = false
        var loggedIn: Boolean = false
        var pinChanged: Boolean = false
    }

    private class OpenTransaction(val number: Long, val clientId: String)

    private val lock = Any()
    private val serialNumber: ByteArray = sha256("virtual-tse-serial:$mountPoint".toByteArray())
    private val publicKey: ByteArray = buildPublicKey()

    private val users = mutableMapOf(
        WormUserId.WORM_USER_ADMIN to UserAuth(deriveDigits(DEFAULT_SEED_BYTES, 0, ADMIN_PIN_LENGTH)),
        WormUserId.WORM_USER_TIME_ADMIN to UserAuth(deriveDigits(DEFAULT_SEED_BYTES, 8, TIME_ADMIN_PIN_LENGTH)),
        WormUserId.WORM_USER_LOGGER to UserAuth(LOGGER_FIXED_PIN.toByteArray()),
    )

    /**
     * TSE 2.0 gives every user its own PUK; TSE 1.x has only the Admin PUK. Modelling
     * the map on both generations keeps one code path — v1 simply never touches the
     * other two entries.
     */
    private val puks = mutableMapOf(
        WormUserId.WORM_USER_ADMIN to deriveDigits(DEFAULT_SEED_BYTES, 16, PUK_LENGTH),
        WormUserId.WORM_USER_TIME_ADMIN to deriveDigits(DEFAULT_SEED_BYTES, 24, PUK_LENGTH),
        WormUserId.WORM_USER_LOGGER to deriveDigits(DEFAULT_SEED_BYTES, 32, PUK_LENGTH),
    )
    private val puksChanged = mutableSetOf<WormUserId>()

    /** Back-compat aliases: the existing v1 code paths address the Admin PUK. */
    private var adminPuk: ByteArray
        get() = puks.getValue(WormUserId.WORM_USER_ADMIN)
        set(value) { puks[WormUserId.WORM_USER_ADMIN] = value }

    private var pukChanged: Boolean
        get() = WormUserId.WORM_USER_ADMIN in puksChanged
        set(value) {
            if (value) puksChanged.add(WormUserId.WORM_USER_ADMIN) else puksChanged.remove(WormUserId.WORM_USER_ADMIN)
        }

    private var pukRemainingRetries: Int = MAX_LOGIN_RETRIES
    private var pukBlocked: Boolean = false

    private var initializationState: WormInitializationState = WormInitializationState.WORM_INIT_UNINITIALIZED
    private var ctssActive: Boolean = false
    private var exportIfCspTestFails: Boolean = false

    private var selfTestPassed: Boolean = false
    private var selfTestAtMs: Long = 0

    private var timeValid: Boolean = false
    private var baseUnixSeconds: Long = 0
    private var timeSetAtMs: Long = 0

    private var keepAliveIntervalSeconds: Int = 0

    /** Wall-clock ms of the last authenticated action, for the v2 idle auto-logout. */
    private var lastAuthActivityAtMs: Long = 0

    /** Test hook: when set, overrides computed free space so the store-full path is reachable. */
    private var simulatedFreeBytes: Long? = null

    private var transactionCounter: Long = 0
    private var signatureCounter: Long = 0
    private val openTransactions = LinkedHashMap<Long, OpenTransaction>()
    private val lastResponses = mutableMapOf<String, WormTransactionResponse>()
    private var lastResponseGlobal: WormTransactionResponse? = null

    private val registeredClients = LinkedHashSet<String>()
    private val entries = ArrayList<LogEntry>()

    // ============================================================================================
    // Info / health
    // ============================================================================================

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            customizationIdentifier = ByteArray(0),
            isDevelopmentFirmware = true,
            capacity = CAPACITY_BYTES,
            size = BASE_USED_BYTES + entries.size.toLong() * TAR_SECTOR_SIZE,
            hasValidTime = timeValid,
            hasPassedSelfTest = selfTestValid(),
            isCtssInterfaceActive = ctssActive,
            isExportEnabledIfCspTestFails = exportIfCspTestFails,
            initializationState = initializationState,
            isDataImportInProgress = false,
            hasChangedPuk = pukChanged,
            hasChangedAdminPin = users.getValue(WormUserId.WORM_USER_ADMIN).pinChanged,
            hasChangedTimeAdminPin = users.getValue(WormUserId.WORM_USER_TIME_ADMIN).pinChanged,
            timeUntilNextSelfTest = timeUntilNextSelfTest(),
            startedTransactions = openTransactions.size.toLong(),
            maxStartedTransactions = MAX_STARTED_TRANSACTIONS,
            createdSignatures = signatureCounter,
            maxSignatures = MAX_SIGNATURES,
            remainingSignatures = MAX_SIGNATURES - signatureCounter,
            maxTimeSynchronizationDelay = TIME_SYNC_INTERVAL_SECONDS,
            maxUpdateDelay = MAX_UPDATE_DELAY_SECONDS,
            tsePublicKey = publicKey,
            timeUntilNextTimeSynchronization = timeUntilNextTimeSync(),
            tseSerialNumber = serialNumber,
            tseDescription = "Virtual Swissbit TSE (software test double)",
            registeredClients = registeredClients.size.toLong(),
            maxRegisteredClients = MAX_REGISTERED_CLIENTS,
            certificateExpirationDate = CERTIFICATE_EXPIRATION_UNIX_SECONDS,
            tarExportSizeInSectors = sectors(tarBytes(entries).size.toLong()),
            tarExportSize = tarBytes(entries).size.toLong(),
            hardwareVersion = HARDWARE_VERSION,
            softwareVersion = if (isV2) SOFTWARE_VERSION_V2 else SOFTWARE_VERSION,
            isTSEv2 = isV2,
            // Reported values, not internal state. Real TSE 1.x hardware does not answer
            // these: worm_info_loggedInUser "for older TSEs will always return
            // WORM_USER_UNAUTHENTICATED", and worm_info_loggedInUserAutoLogOutTimeout "for
            // older TSEs will always return 0xffff". Reporting the true session on v1 would
            // make the double MORE truthful than the hardware, which is a trap: a test could
            // assert it, pass here, and be wrong about real devices.
            //
            // Internal authorization (rejectIfTimeAdminForDataAccess and friends) deliberately
            // keeps using currentlyLoggedInUser(), which tracks the real session on both
            // generations.
            loggedInUser = if (isV2) currentlyLoggedInUser() else WormUserId.WORM_USER_UNAUTHENTICATED,
            loggedInUserAutoLogOutTimeout = if (isV2) autoLogOutTimeoutSeconds() else V1_NO_AUTO_LOGOUT,
            hasChangedAdminPuk = WormUserId.WORM_USER_ADMIN in puksChanged,
            hasChangedTimeAdminPuk = WormUserId.WORM_USER_TIME_ADMIN in puksChanged,
            hasChangedLoggerPuk = WormUserId.WORM_USER_LOGGER in puksChanged,
            hasChangedLoggerPin = users.getValue(WormUserId.WORM_USER_LOGGER).pinChanged,
            formFactor = "uSD",
        )
    }

    fun flashHealthSummary(): WormFlashHealthSummary = WormFlashHealthSummary(
        uncorrectableEccErrorsValue = 0,
        percentageRemainingSpareBlocksValue = 100,
        percentageRemainingEraseCountsValue = 100,
        percentageRemainingTenYearsDataRetentionValue = 100,
        needsReplacementValue = false,
    )

    fun keepAliveConfigure(intervalSeconds: Int) = synchronized(lock) {
        keepAliveIntervalSeconds = intervalSeconds
    }

    fun keepAliveDisable() = synchronized(lock) {
        keepAliveIntervalSeconds = 0
    }

    // ============================================================================================
    // Lifecycle
    // ============================================================================================

    fun runSelfTest(clientId: String?) = synchronized(lock) {
        // v1 requires a registered client; v2 dropped that requirement but added an
        // initialization precondition and bars TimeAdmin from running the self test.
        if (!isV2 && clientId == null) err(WormError.WORM_ERROR_INVALID_PARAMETER)
        // v1 wants a *registered* client, not merely a non-null one. Without this the double
        // accepted any string and startup appeared to succeed for a Kasse the TSE had never
        // heard of.
        if (!isV2 && clientId !in registeredClients) {
            err(WormError.WORM_ERROR_CLIENT_NOT_REGISTERED)
        }
        requireInitializedOnV2()
        if (isV2 && currentlyLoggedInUser() == WormUserId.WORM_USER_TIME_ADMIN) {
            err(WormError.WORM_ERROR_NOT_AUTHORIZED)
        }
        selfTestPassed = true
        selfTestAtMs = System.currentTimeMillis()
    }

    fun initialize() = synchronized(lock) {
        requireAdmin()
        // v1 requires an explicit passing self test before initialization (the certified
        // order). v2 must NOT: there the self test itself requires an initialized TSE, and
        // the hardware runs it automatically when needed -- demanding one first would be a
        // deadlock that no real device has.
        if (!isV2) requireSelfTest()
        when (initializationState) {
            WormInitializationState.WORM_INIT_INITIALIZED -> err(WormError.WORM_ERROR_TSE_ALREADY_INITIALIZED)
            WormInitializationState.WORM_INIT_DECOMMISSIONED -> err(WormError.WORM_ERROR_TSE_DECOMMISSIONED)
            WormInitializationState.WORM_INIT_UNINITIALIZED -> Unit
        }
        if (!pukChanged) err(WormError.WORM_ERROR_WRONG_STATE_NEEDS_PUK_CHANGE)
        if (!users.getValue(WormUserId.WORM_USER_ADMIN).pinChanged ||
            !users.getValue(WormUserId.WORM_USER_TIME_ADMIN).pinChanged
        ) {
            err(WormError.WORM_ERROR_WRONG_STATE_NEEDS_PIN_CHANGE)
        }
        initializationState = WormInitializationState.WORM_INIT_INITIALIZED
    }

    fun decommission() = synchronized(lock) {
        requireAdmin()
        when (initializationState) {
            WormInitializationState.WORM_INIT_UNINITIALIZED -> err(WormError.WORM_ERROR_TSE_NOT_INITIALIZED)
            WormInitializationState.WORM_INIT_DECOMMISSIONED -> err(WormError.WORM_ERROR_TSE_DECOMMISSIONED)
            WormInitializationState.WORM_INIT_INITIALIZED -> Unit
        }
        if (openTransactions.isNotEmpty()) err(WormError.WORM_ERROR_TSE_HAS_UNFINISHED_TRANSACTIONS)
        initializationState = WormInitializationState.WORM_INIT_DECOMMISSIONED
    }

    /** Development-sample behavior: full reset back to factory state, no auth required. */
    fun factoryReset() = synchronized(lock) {
        users[WormUserId.WORM_USER_ADMIN] = UserAuth(deriveDigits(DEFAULT_SEED_BYTES, 0, ADMIN_PIN_LENGTH))
        users[WormUserId.WORM_USER_TIME_ADMIN] = UserAuth(deriveDigits(DEFAULT_SEED_BYTES, 8, TIME_ADMIN_PIN_LENGTH))
        users[WormUserId.WORM_USER_LOGGER] = UserAuth(LOGGER_FIXED_PIN.toByteArray())
        puks[WormUserId.WORM_USER_ADMIN] = deriveDigits(DEFAULT_SEED_BYTES, 16, PUK_LENGTH)
        puks[WormUserId.WORM_USER_TIME_ADMIN] = deriveDigits(DEFAULT_SEED_BYTES, 24, PUK_LENGTH)
        puks[WormUserId.WORM_USER_LOGGER] = deriveDigits(DEFAULT_SEED_BYTES, 32, PUK_LENGTH)
        puksChanged.clear()
        lastAuthActivityAtMs = 0
        pukRemainingRetries = MAX_LOGIN_RETRIES
        pukBlocked = false
        initializationState = WormInitializationState.WORM_INIT_UNINITIALIZED
        ctssActive = false
        exportIfCspTestFails = false
        selfTestPassed = false
        selfTestAtMs = 0
        timeValid = false
        baseUnixSeconds = 0
        timeSetAtMs = 0
        transactionCounter = 0
        signatureCounter = 0
        openTransactions.clear()
        lastResponses.clear()
        lastResponseGlobal = null
        registeredClients.clear()
        entries.clear()
    }

    /** Combined factory setup (unused by pos-tse main, provided for API completeness). */
    fun setup(
        credentialSeed: ByteArray?,
        adminPukNew: ByteArray?,
        adminPinNew: ByteArray?,
        timeAdminPinNew: ByteArray?,
        clientId: String?,
    ) = synchronized(lock) {
        if (credentialSeed == null || adminPukNew == null || adminPinNew == null ||
            timeAdminPinNew == null || clientId == null
        ) {
            err(WormError.WORM_ERROR_INVALID_PARAMETER)
        }
        if (initializationState != WormInitializationState.WORM_INIT_UNINITIALIZED) {
            err(WormError.WORM_ERROR_TSE_ALREADY_INITIALIZED)
        }
        selfTestPassed = true
        selfTestAtMs = System.currentTimeMillis()
        adminPuk = adminPukNew.copyOf()
        pukChanged = true
        users.getValue(WormUserId.WORM_USER_ADMIN).let { it.pin = adminPinNew.copyOf(); it.pinChanged = true }
        users.getValue(WormUserId.WORM_USER_TIME_ADMIN).let { it.pin = timeAdminPinNew.copyOf(); it.pinChanged = true }
        registeredClients.add(clientId)
        initializationState = WormInitializationState.WORM_INIT_INITIALIZED
    }

    fun updateTime(unixTimeSeconds: Long) = synchronized(lock) {
        if (!loggedIn(WormUserId.WORM_USER_ADMIN) && !loggedIn(WormUserId.WORM_USER_TIME_ADMIN)) {
            err(WormError.WORM_ERROR_NOT_AUTHORIZED)
        }
        requireSelfTest()
        baseUnixSeconds = unixTimeSeconds
        timeSetAtMs = System.currentTimeMillis()
        timeValid = true
    }

    fun ctssEnable() = synchronized(lock) {
        // TSE 2.0 removed the CTSS interface and the CSP-export toggle: no-ops, not errors.
        if (isV2) return@synchronized
        requireAdmin()
        ctssActive = true
    }

    fun ctssDisable() = synchronized(lock) {
        // TSE 2.0 removed the CTSS interface and the CSP-export toggle: no-ops, not errors.
        if (isV2) return@synchronized
        requireAdmin()
        ctssActive = false
    }

    fun enableExportIfCspTestFails() = synchronized(lock) {
        // TSE 2.0 removed the CTSS interface and the CSP-export toggle: no-ops, not errors.
        if (isV2) return@synchronized
        requireAdmin()
        exportIfCspTestFails = true
    }

    fun disableExportIfCspTestFails() = synchronized(lock) {
        // TSE 2.0 removed the CTSS interface and the CSP-export toggle: no-ops, not errors.
        if (isV2) return@synchronized
        requireAdmin()
        exportIfCspTestFails = false
    }

    // ============================================================================================
    // Firmware (virtual device never has updates)
    // ============================================================================================

    fun firmwareUpdateCheck(): WormFirmwareUpdateCheckResponse = WormFirmwareUpdateCheckResponse(
        isAvailableValue = false,
        downloadUrlValue = "",
    )

    fun firmwareUpdateBundledAvailable(): WormTseFirmwareUpdate = WormTseFirmwareUpdate.WORM_FW_NONE

    fun firmwareUpdateApplyBundled(): Unit = err(WormError.WORM_ERROR_FWU_NOT_AVAILABLE)

    fun firmwareUpdate(firmware: ByteArray?): Unit =
        if (firmware == null) err(WormError.WORM_ERROR_INVALID_PARAMETER) else err(WormError.WORM_ERROR_FWU_WRONG_FORMAT)

    // ============================================================================================
    // Users / authentication
    // ============================================================================================

    fun deriveInitialCredentials(credentialSeed: ByteArray?): WormInitialCredentials {
        if (credentialSeed == null || credentialSeed.size != CREDENTIAL_SEED_LENGTH) {
            err(WormError.WORM_ERROR_INVALID_PARAMETER)
        }
        return WormInitialCredentials(
            adminPukValue = deriveDigits(credentialSeed, 16, PUK_LENGTH),
            adminPinValue = deriveDigits(credentialSeed, 0, ADMIN_PIN_LENGTH),
            timeAdminPinValue = deriveDigits(credentialSeed, 8, TIME_ADMIN_PIN_LENGTH),
            timeAdminPukValue = deriveDigits(credentialSeed, 24, PUK_LENGTH),
            loggerPukValue = deriveDigits(credentialSeed, 32, PUK_LENGTH),
            loggerPinValue = deriveDigits(credentialSeed, 40, ADMIN_PIN_LENGTH),
        )
    }

    /** SDK 6.0: changes the PUK of a specific user (TSE 2.0 has one per user). */
    fun changePukExt(userId: WormUserId?, oldPuk: ByteArray?, newPuk: ByteArray?) = synchronized(lock) {
        val id = userId ?: err(WormError.WORM_ERROR_INVALID_PARAMETER)
        if (oldPuk == null || newPuk == null) err(WormError.WORM_ERROR_INVALID_PARAMETER)
        val current = puks[id] ?: err(WormError.WORM_ERROR_INVALID_PARAMETER)
        if (!oldPuk.contentEquals(current)) err(WormError.WORM_ERROR_AUTHENTICATION_FAILED)
        puks[id] = newPuk.copyOf()
        puksChanged.add(id)
        Unit
    }

    fun needsSetup(): Boolean = synchronized(lock) {
        initializationState == WormInitializationState.WORM_INIT_UNINITIALIZED
    }

    /**
     * SDK 6.0 setup helper. Orders the steps correctly for the attached generation:
     * on v2 the TSE must be initialized before a client can be registered, and the time
     * must be set — the reverse of the certified v1 order that the current production
     * code implements by hand.
     */
    fun setupExt(
        credentialSeed: ByteArray?,
        adminPukNew: ByteArray?,
        adminPinNew: ByteArray?,
        timeAdminPinNew: ByteArray?,
        clientId: String?,
        @Suppress("UNUSED_PARAMETER") enableAutopilot: Boolean,
    ) = synchronized(lock) {
        if (credentialSeed == null || adminPukNew == null || adminPinNew == null ||
            timeAdminPinNew == null || clientId == null
        ) {
            err(WormError.WORM_ERROR_INVALID_PARAMETER)
        }
        if (initializationState != WormInitializationState.WORM_INIT_UNINITIALIZED) {
            err(WormError.WORM_ERROR_TSE_ALREADY_INITIALIZED)
        }
        requireValidClientId(clientId)

        selfTestPassed = true
        selfTestAtMs = System.currentTimeMillis()

        puks[WormUserId.WORM_USER_ADMIN] = adminPukNew.copyOf()
        puksChanged.addAll(puks.keys)
        users.getValue(WormUserId.WORM_USER_ADMIN).let { it.pin = adminPinNew.copyOf(); it.pinChanged = true }
        users.getValue(WormUserId.WORM_USER_TIME_ADMIN).let { it.pin = timeAdminPinNew.copyOf(); it.pinChanged = true }
        users.getValue(WormUserId.WORM_USER_LOGGER).pinChanged = true

        if (!isV2) ctssActive = true
        initializationState = WormInitializationState.WORM_INIT_INITIALIZED

        // v2 sets the time as part of setup; v1 leaves it to the ERS.
        if (isV2) {
            baseUnixSeconds = System.currentTimeMillis() / 1000
            timeSetAtMs = System.currentTimeMillis()
            timeValid = true
        }

        registeredClients.add(clientId)

        // Both generations leave Admin logged in after setup.
        users.values.forEach { it.loggedIn = false }
        users.getValue(WormUserId.WORM_USER_ADMIN).loggedIn = true
        lastAuthActivityAtMs = System.currentTimeMillis()
    }

    /**
     * v2 bars TimeAdmin from exporting or reading entries.
     *
     * This is why the startup sequence must log TimeAdmin out immediately after
     * updating the time: leaving it as the resting user breaks every later export.
     */
    private fun rejectIfTimeAdminForDataAccess() {
        if (isV2 && currentlyLoggedInUser() == WormUserId.WORM_USER_TIME_ADMIN) {
            err(WormError.WORM_ERROR_NOT_AUTHORIZED)
        }
    }

    /**
     * Test hook for the store-full path. Deliberately `public`, not `internal`: Kotlin
     * mangles internal function names on the JVM (`name$module`), and tests reach this
     * reflectively because the virtualWorm source set is not on their compile classpath.
     * The whole class ships only in that test-only source set, so nothing is exposed
     * to production.
     */
    /**
     * Test hook: drops the time back to invalid so the stale-TSE recovery path is reachable.
     * Public, not internal — Kotlin mangles internal names on the JVM and tests reach this
     * reflectively.
     */
    fun invalidateTimeForTest() = synchronized(lock) {
        timeValid = false
    }

    /**
     * Marks the self test as due, the way a real TSE does once its 25-hour window lapses.
     *
     * Without this the startup self-test path is unreachable in tests: setup leaves the self
     * test passed, so checkSelfTestPassed() short-circuits and nothing after it ever runs.
     */
    /**
     * The PUK attempts the device will still grant. Exposed because it is the one budget a
     * test cannot infer by probing — probing the PUK is what spends it.
     */
    fun pukRemainingRetriesForTest(): Int = synchronized(lock) { pukRemainingRetries }

    fun expireSelfTestForTest() = synchronized(lock) {
        selfTestPassed = false
        selfTestAtMs = 0L
    }

    fun setSimulatedFreeBytes(freeBytes: Long) = synchronized(lock) {
        simulatedFreeBytes = freeBytes
    }

    private fun freeBytes(): Long =
        simulatedFreeBytes ?: (CAPACITY_BYTES - (BASE_USED_BYTES + entries.size.toLong() * TAR_SECTOR_SIZE))

    /**
     * TSE 2.0 returns STORE_FULL while roughly 1 MB is still free, reserving that space
     * for the login and time update needed to export and then delete. v1 has no reserve,
     * so cash registers must start exporting far earlier on v2.
     */
    private fun requireStoreNotFull() {
        val threshold = if (isV2) V2_STORE_FULL_RESERVE_BYTES else 0L
        if (freeBytes() <= threshold) err(WormError.WORM_ERROR_STORE_FULL)
    }

    /** TSE 2.0 removed hardware-side filtering; the ERS must filter the TAR itself. */
    private fun rejectFilteredExportOnV2() {
        if (isV2) err(WormError.WORM_ERROR_CMD_NOT_SUPPORTED)
    }

    /** v2 requires an initialized TSE for client management and the self test. */
    private fun requireInitializedOnV2() {
        if (!isV2) return
        when (initializationState) {
            WormInitializationState.WORM_INIT_UNINITIALIZED -> err(WormError.WORM_ERROR_TSE_NOT_INITIALIZED)
            WormInitializationState.WORM_INIT_DECOMMISSIONED -> err(WormError.WORM_ERROR_TSE_DECOMMISSIONED)
            WormInitializationState.WORM_INIT_INITIALIZED -> Unit
        }
    }

    /** TSE 2.0 rejects '/', ':' and '?' in a client ID. */
    private fun requireValidClientId(clientId: String) {
        if (clientId.isBlank()) err(WormError.WORM_ERROR_INVALID_PARAMETER)
        if (isV2 && clientId.any { it in FORBIDDEN_CLIENT_ID_CHARS }) {
            err(WormError.WORM_ERROR_TSE_SEAPI_INVALID_CLIENT_ID_CHARACTER)
        }
    }

    fun login(userId: WormUserId?, pin: ByteArray?) = synchronized(lock) {
        // Host-side fault injection point (see VirtualWormRegistry.raiseArmedFault): raised before
        // any device state is consulted, because a card that has gone away answers nothing at all.
        VirtualWormRegistry.raiseArmedFault()
        val user = authFor(userId)
        if (pin == null) err(WormError.WORM_ERROR_INVALID_PARAMETER)
        // "Before any user can be logged in, the initial PUK must have been changed"
        // (WormStore.user_login). A fresh device permits only the PUK change and credential
        // derivation until then — so a login attempted before it is a state error, not an
        // auth failure, and must not spend a login attempt. Modelling this is what turns a
        // login-before-PUK-change bug from green-in-tests into a caught failure.
        if (!pukChanged) err(WormError.WORM_ERROR_WRONG_STATE_NEEDS_PUK_CHANGE)
        if (user.blocked) {
            throw WormAuthenticationFailedException(WormError.WORM_ERROR_AUTHENTICATION_PIN_BLOCKED.swigValue(), 0)
        }
        if (!pin.contentEquals(user.pin)) {
            user.remainingRetries -= 1
            if (user.remainingRetries <= 0) {
                user.remainingRetries = 0
                user.blocked = true
            }
            throw WormAuthenticationFailedException(
                WormError.WORM_ERROR_AUTHENTICATION_FAILED.swigValue(),
                user.remainingRetries,
            )
        }
        user.remainingRetries = MAX_LOGIN_RETRIES
        if (isV2) {
            // TSE 2.0 allows exactly one logged-in user; a new login logs out the current one.
            users.values.forEach { it.loggedIn = false }
        }
        user.loggedIn = true
        lastAuthActivityAtMs = System.currentTimeMillis()
    }

    fun logout(userId: WormUserId?) = synchronized(lock) {
        // On v2 the userId is ignored and whoever is logged in is logged out.
        val user = if (isV2) {
            users.entries.firstOrNull { it.value.loggedIn }?.value ?: authFor(userId)
        } else {
            authFor(userId)
        }
        if (!user.loggedIn) err(WormError.WORM_ERROR_AUTHENTICATION_USER_NOT_LOGGED_IN)
        user.loggedIn = false
    }

    fun unblock(userId: WormUserId?, puk: ByteArray?, newPin: ByteArray?) = synchronized(lock) {
        val user = authFor(userId)
        if (puk == null || newPin == null) err(WormError.WORM_ERROR_INVALID_PARAMETER)
        checkPuk(puk)
        user.pin = newPin.copyOf()
        user.pinChanged = true
        user.blocked = false
        user.remainingRetries = MAX_LOGIN_RETRIES
        user.loggedIn = false
    }

    fun changePuk(oldPuk: ByteArray?, newPuk: ByteArray?) = synchronized(lock) {
        if (oldPuk == null || newPuk == null) err(WormError.WORM_ERROR_INVALID_PARAMETER)
        checkPuk(oldPuk)
        adminPuk = newPuk.copyOf()
        pukChanged = true
    }

    fun changePin(userId: WormUserId?, oldPin: ByteArray?, newPin: ByteArray?) = synchronized(lock) {
        val user = authFor(userId)
        if (oldPin == null || newPin == null) err(WormError.WORM_ERROR_INVALID_PARAMETER)
        if (!user.loggedIn) err(WormError.WORM_ERROR_AUTHENTICATION_USER_NOT_LOGGED_IN)
        if (!oldPin.contentEquals(user.pin)) {
            user.remainingRetries -= 1
            if (user.remainingRetries <= 0) {
                user.remainingRetries = 0
                user.blocked = true
            }
            throw WormAuthenticationFailedException(
                WormError.WORM_ERROR_AUTHENTICATION_FAILED.swigValue(),
                user.remainingRetries,
            )
        }
        user.pin = newPin.copyOf()
        user.pinChanged = true
    }

    // ============================================================================================
    // Client registration
    // ============================================================================================

    fun registerClient(clientId: String?) = synchronized(lock) {
        // v2 requires an initialized TSE and a set time BEFORE a client can be registered
        // -- the reverse of the certified v1 order, and the reason the current production
        // setup sequence fails on TSE 2.0.
        requireInitializedOnV2()
        requireAdmin()
        if (isV2 && !timeValid) err(WormError.WORM_ERROR_NO_TIME_SET)
        // No self-test precondition. The SDK documents this command as needing only Admin
        // (plus a valid time for Logger on v2), and requiring a passed self test here would
        // deadlock a fresh v1 device: the v1 self test needs a registered client, so neither
        // could ever go first.
        if (clientId.isNullOrBlank()) err(WormError.WORM_ERROR_INVALID_PARAMETER)
        requireValidClientId(clientId)
        if (clientId !in registeredClients && registeredClients.size >= MAX_REGISTERED_CLIENTS) {
            err(WormError.WORM_ERROR_MAX_REGISTERED_CLIENTS_REACHED)
        }
        registeredClients.add(clientId)
        Unit
    }

    fun deregisterClient(clientId: String?) = synchronized(lock) {
        requireInitializedOnV2()
        requireAdmin()
        if (isV2 && !timeValid) err(WormError.WORM_ERROR_NO_TIME_SET)
        if (clientId == null) err(WormError.WORM_ERROR_INVALID_PARAMETER)
        if (clientId !in registeredClients) err(WormError.WORM_ERROR_CLIENT_NOT_REGISTERED)
        if (openTransactions.values.any { it.clientId == clientId }) {
            err(WormError.WORM_ERROR_CLIENT_HAS_UNFINISHED_TRANSACTIONS)
        }
        registeredClients.remove(clientId)
        Unit
    }

    fun listRegisteredClients(): Array<String> = synchronized(lock) {
        registeredClients.toTypedArray()
    }

    // ============================================================================================
    // Transactions
    // ============================================================================================

    fun transactionStart(clientId: String?, processData: ByteArray?, processType: String?): WormTransactionResponse =
        synchronized(lock) {
            // Host-side fault injection point (see VirtualWormRegistry.raiseArmedFault): a stick that
            // has gone away fails the signing call before any counter moves.
            VirtualWormRegistry.raiseArmedFault()
            val client = requireTransactionReady(clientId)
            if (openTransactions.size >= MAX_STARTED_TRANSACTIONS) {
                err(WormError.WORM_ERROR_MAX_PARALLEL_TRANSACTIONS)
            }
            transactionCounter += 1
            val number = transactionCounter
            openTransactions[number] = OpenTransaction(number, client)
            sign("start", client, number, processData, processType)
        }

    fun transactionUpdate(
        clientId: String?,
        transactionNumber: Long,
        processData: ByteArray?,
        processType: String?,
    ): WormTransactionResponse = synchronized(lock) {
        // Host-side fault injection point (see VirtualWormRegistry.raiseArmedFault): a stick that
        // has gone away fails the signing call before any counter moves.
        VirtualWormRegistry.raiseArmedFault()
        val client = requireTransactionReady(clientId)
        if (transactionNumber !in openTransactions) err(WormError.WORM_ERROR_TRANSACTION_NOT_STARTED)
        sign("update", client, transactionNumber, processData, processType)
    }

    fun transactionFinish(
        clientId: String?,
        transactionNumber: Long,
        processData: ByteArray?,
        processType: String?,
    ): WormTransactionResponse = synchronized(lock) {
        // Host-side fault injection point (see VirtualWormRegistry.raiseArmedFault): a stick that
        // has gone away fails the signing call before any counter moves.
        VirtualWormRegistry.raiseArmedFault()
        val client = requireTransactionReady(clientId)
        if (transactionNumber !in openTransactions) err(WormError.WORM_ERROR_TRANSACTION_NOT_STARTED)
        val response = sign("finish", client, transactionNumber, processData, processType)
        openTransactions.remove(transactionNumber)
        response
    }

    fun lastResponse(clientId: String?): WormTransactionResponse = synchronized(lock) {
        val response = if (clientId == null) lastResponseGlobal else lastResponses[clientId]
        response ?: err(WormError.WORM_ERROR_NO_LAST_TRANSACTION)
    }

    fun listStartedTransactions(clientId: String?): LongArray = synchronized(lock) {
        openTransactions.values
            .filter { clientId == null || it.clientId == clientId }
            .map { it.number }
            .sorted()
            .toLongArray()
    }

    // ============================================================================================
    // Log entries
    // ============================================================================================

    fun entryCount(): Int = synchronized(lock) { entries.size }

    fun entryAt(position: Int): LogEntry? = synchronized(lock) { entries.getOrNull(position) }

    fun indexOfEntryId(id: Long): Int = synchronized(lock) { entries.indexOfFirst { it.id == id } }

    fun logMessageCertificate(): ByteArray {
        val body = java.util.Base64.getMimeEncoder().encodeToString(sha256(publicKey) + sha256(serialNumber))
        return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n".toByteArray()
    }

    // ============================================================================================
    // Export
    // ============================================================================================

    fun exportTar(sink: (ByteArray) -> Int) = synchronized(lock) {
        rejectIfTimeAdminForDataAccess()
        streamTar(tarBytes(entries), sink)
    }

    fun exportTarFilteredTime(startDate: Long, endDate: Long, clientId: String?, sink: (ByteArray) -> Int) =
        synchronized(lock) {
            rejectFilteredExportOnV2()
            rejectIfTimeAdminForDataAccess()
            val filtered = entries.filter {
                it.logTime in startDate..endDate && (clientId.isNullOrEmpty() || it.clientId == clientId)
            }
            if (filtered.isEmpty()) err(WormError.WORM_ERROR_EXPORT_NO_DATA_AVAILABLE)
            streamTar(tarBytes(filtered), sink)
        }

    fun exportTarFilteredTransaction(start: Long, end: Long, clientId: String?, sink: (ByteArray) -> Int) =
        synchronized(lock) {
            rejectFilteredExportOnV2()
            rejectIfTimeAdminForDataAccess()
            val filtered = entries.filter {
                it.transactionNumber in start..end && (clientId.isNullOrEmpty() || it.clientId == clientId)
            }
            if (filtered.isEmpty()) err(WormError.WORM_ERROR_EXPORT_NO_DATA_AVAILABLE)
            streamTar(tarBytes(filtered), sink)
        }

    fun exportTarIncremental(
        state: ByteArray?,
        maxExportSize: Long,
        sink: (ByteArray, Long, Long) -> Int,
    ): WormExportTarIncrementalResponse = synchronized(lock) {
        val exportedUpTo = decodeIncrementalState(state)
        if (maxExportSize < TAR_SECTOR_SIZE) err(WormError.WORM_ERROR_INCREMENTAL_EXPORT_LIMIT_TOO_LOW)
        if (exportedUpTo >= signatureCounter) err(WormError.WORM_ERROR_INCREMENTAL_EXPORT_NO_DATA)
        val pending = entries.filter { it.id > exportedUpTo }
        val tar = tarBytes(pending)
        val totalBlocks = sectors(tar.size.toLong())
        var processed = 0L
        var offset = 0
        while (offset < tar.size) {
            val chunk = tar.copyOfRange(offset, minOf(offset + EXPORT_CHUNK_SIZE, tar.size))
            offset += chunk.size
            processed = minOf(totalBlocks, processed + sectors(chunk.size.toLong()))
            if (sink(chunk, processed, totalBlocks) != 0) err(WormError.WORM_ERROR_EXPORT_FAILED)
        }
        WormExportTarIncrementalResponse(
            newStateValue = encodeIncrementalState(signatureCounter),
            firstSignatureCounterValue = exportedUpTo + 1,
            lastSignatureCounterValue = signatureCounter,
            allDataExportedValue = true,
        )
    }

    fun exportIncrementalSizeInSectors(state: ByteArray?): Long = synchronized(lock) {
        sectors(exportIncrementalSize(state))
    }

    fun exportIncrementalSize(state: ByteArray?): Long = synchronized(lock) {
        val exportedUpTo = decodeIncrementalState(state)
        tarBytes(entries.filter { it.id > exportedUpTo }).size.toLong()
    }

    fun exportLcmFile(path: String?): String {
        if (path == null) err(WormError.WORM_ERROR_INVALID_PARAMETER)
        return "virtual-lcm;serial=${hex(serialNumber, 8)};entries=${entryCount()}"
    }

    fun exportDeleteStoredData() = synchronized(lock) {
        requireAdmin()
        // v2 additionally needs a valid time, which is why the documented delete flow is
        // login Admin -> updateTime -> export -> delete. Updating the time *after* the
        // export writes a log message that is not in it, permanently blocking deletion.
        if (isV2 && !timeValid) err(WormError.WORM_ERROR_NO_TIME_SET)
        if (openTransactions.isNotEmpty()) err(WormError.WORM_ERROR_TSE_HAS_UNFINISHED_TRANSACTIONS)
        entries.clear()
    }

    // ============================================================================================
    // Internals
    // ============================================================================================

    private fun err(error: WormError): Nothing = throw WormException(error.swigValue())

    private fun loggedIn(userId: WormUserId): Boolean {
        expireIdleLoginIfNeeded()
        return users.getValue(userId).loggedIn
    }

    /**
     * Applies the v2 40-minute idle auto-logout before any authorization check.
     *
     * TSE 2.0 ends a session after 40 minutes without an authenticated action, so no
     * caller may assume a login taken earlier is still valid.
     */
    private fun expireIdleLoginIfNeeded() {
        if (!isV2 || lastAuthActivityAtMs == 0L) return
        if (System.currentTimeMillis() - lastAuthActivityAtMs >= AUTO_LOGOUT_MS) {
            users.values.forEach { it.loggedIn = false }
            lastAuthActivityAtMs = 0
        }
    }

    /**
     * Mirrors the SDK's automatic Logger login for transaction commands on v2.
     *
     * Logging Logger in evicts any Admin or TimeAdmin session, exactly as on hardware —
     * which is why no caller may assume an earlier login survived a transaction. The
     * Logger PIN is set by the SDK during setup and never known to the ERS, so this
     * needs no stored credential.
     */
    private fun ensureLoggerLoggedInForTransaction() {
        if (!isV2) return
        expireIdleLoginIfNeeded()
        val loggerUser = users.getValue(WormUserId.WORM_USER_LOGGER)
        if (!loggerUser.loggedIn) {
            users.values.forEach { it.loggedIn = false }
            loggerUser.loggedIn = true
        }
        lastAuthActivityAtMs = System.currentTimeMillis()
    }

    internal fun currentlyLoggedInUser(): WormUserId {
        expireIdleLoginIfNeeded()
        return users.entries.firstOrNull { it.value.loggedIn }?.key
            ?: WormUserId.WORM_USER_UNAUTHENTICATED
    }

    internal fun autoLogOutTimeoutSeconds(): Int {
        expireIdleLoginIfNeeded()
        if (!isV2 || lastAuthActivityAtMs == 0L) return 0
        val elapsed = System.currentTimeMillis() - lastAuthActivityAtMs
        return ((AUTO_LOGOUT_MS - elapsed).coerceAtLeast(0) / 1000).toInt()
    }

    private fun requireAdmin() {
        if (!loggedIn(WormUserId.WORM_USER_ADMIN)) err(WormError.WORM_ERROR_NOT_AUTHORIZED)
    }

    private fun selfTestValid(): Boolean =
        selfTestPassed && (System.currentTimeMillis() - selfTestAtMs) / 1000 < SELF_TEST_INTERVAL_SECONDS

    /**
     * v1 refuses the command and tells the ERS to run a self test. v2 instead runs it
     * itself inside the hardware, which is why the guide states that on v2 "commands can
     * no longer fail with WORM_ERROR_WRONG_STATE_NEEDS_SELF_TEST". Modelled as an implicit
     * pass so v2 code paths see the behaviour a real device would give them.
     *
     * (An ERS is still advised to call the self test explicitly at a chosen time on v2, to
     * control *when* the several-second pause happens — but it is no longer a
     * precondition.)
     */
    private fun requireSelfTest() {
        if (selfTestValid()) return
        if (isV2) {
            selfTestPassed = true
            selfTestAtMs = System.currentTimeMillis()
            return
        }
        err(WormError.WORM_ERROR_WRONG_STATE_NEEDS_SELF_TEST_PASSED)
    }

    private fun authFor(userId: WormUserId?): UserAuth =
        users[userId] ?: err(WormError.WORM_ERROR_INVALID_PARAMETER)

    private fun checkPuk(puk: ByteArray) {
        if (pukBlocked) {
            throw WormAuthenticationFailedException(WormError.WORM_ERROR_AUTHENTICATION_PIN_BLOCKED.swigValue(), 0)
        }
        if (!puk.contentEquals(adminPuk)) {
            pukRemainingRetries -= 1
            if (pukRemainingRetries <= 0) {
                pukRemainingRetries = 0
                pukBlocked = true
            }
            throw WormAuthenticationFailedException(
                WormError.WORM_ERROR_AUTHENTICATION_FAILED.swigValue(),
                pukRemainingRetries,
            )
        }
        pukRemainingRetries = MAX_LOGIN_RETRIES
    }

    /**
     * Shared precondition for transaction_start/update/finish — the only three callers,
     * which is what makes it the right place for the automatic Logger login.
     */
    private fun requireTransactionReady(clientId: String?): String {
        // Order matters: the vendor notes login and time update still succeed while the
        // store is full, so the Logger login happens before the space check.
        ensureLoggerLoggedInForTransaction()
        requireStoreNotFull()
        when (initializationState) {
            WormInitializationState.WORM_INIT_UNINITIALIZED -> err(WormError.WORM_ERROR_TSE_NOT_INITIALIZED)
            WormInitializationState.WORM_INIT_DECOMMISSIONED -> err(WormError.WORM_ERROR_TSE_DECOMMISSIONED)
            WormInitializationState.WORM_INIT_INITIALIZED -> Unit
        }
        requireSelfTest()
        if (!timeValid) err(WormError.WORM_ERROR_NO_TIME_SET)
        if (clientId == null) err(WormError.WORM_ERROR_INVALID_PARAMETER)
        if (clientId !in registeredClients) err(WormError.WORM_ERROR_CLIENT_NOT_REGISTERED)
        return clientId
    }

    private fun deviceNow(): Long =
        if (!timeValid) 0 else baseUnixSeconds + (System.currentTimeMillis() - timeSetAtMs) / 1000

    private fun timeUntilNextSelfTest(): Long =
        if (!selfTestPassed) {
            0
        } else {
            (SELF_TEST_INTERVAL_SECONDS - (System.currentTimeMillis() - selfTestAtMs) / 1000).coerceAtLeast(0)
        }

    private fun timeUntilNextTimeSync(): Long =
        if (!timeValid) {
            0
        } else {
            (TIME_SYNC_INTERVAL_SECONDS - (System.currentTimeMillis() - timeSetAtMs) / 1000).coerceAtLeast(0)
        }

    private fun sign(
        operation: String,
        clientId: String,
        transactionNumber: Long,
        processData: ByteArray?,
        processType: String?,
    ): WormTransactionResponse {
        if (signatureCounter >= MAX_SIGNATURES) err(WormError.WORM_ERROR_TRANSACTION_SIGNATURES_EXCEEDED)
        signatureCounter += 1
        val logTime = deviceNow()
        val data = processData ?: ByteArray(0)
        val head = sha256(serialNumber + longBytes(signatureCounter) + longBytes(transactionNumber) + data)
        val signature = head + sha256(head)
        val operationType = when (operation) {
            "start" -> "StartTransaction"
            "update" -> "UpdateTransaction"
            else -> "FinishTransaction"
        }
        val logMessage = buildTransactionLogMessage(
            operationType = operationType,
            clientId = clientId,
            processData = data,
            processType = processType ?: "",
            transactionNumber = transactionNumber,
            sigCounter = signatureCounter,
            logTime = logTime,
            signature = signature,
        )
        if (entries.size >= MAX_ENTRIES) entries.removeAt(0)
        entries.add(
            LogEntry(
                id = signatureCounter,
                type = WormEntryType.WORM_ENTRY_TYPE_TRANSACTION,
                logMessage = logMessage,
                processData = data.copyOf(),
                transactionNumber = transactionNumber,
                logTime = logTime,
                clientId = clientId,
            ),
        )
        val response = WormTransactionResponse(
            logTimeValue = logTime,
            serialNumberValue = serialNumber,
            signatureCounterValue = signatureCounter,
            signatureValue = signature,
            transactionNumberValue = transactionNumber,
        )
        lastResponses[clientId] = response
        lastResponseGlobal = response
        return response
    }

    // ============================================================================================
    // ASN.1 DER — real TR-03151 transaction log messages (so parser round-trips match hardware)
    // ============================================================================================

    /** id-SE-API-transaction-log: 0.4.0.127.0.7.3.7.1.1 */
    private val oidTransactionLog = byteArrayOf(0x04, 0x00, 0x7F, 0x00, 0x07, 0x03, 0x07, 0x01, 0x01)

    /** ecdsa-plain-SHA384: 0.4.0.127.0.7.1.1.4.1.3 */
    private val oidEcdsaPlainSha384 = byteArrayOf(0x04, 0x00, 0x7F, 0x00, 0x07, 0x01, 0x01, 0x04, 0x01, 0x03)

    private fun derLen(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }

    private fun derTlv(tag: Int, value: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + derLen(value.size) + value

    /** Minimal positive-INTEGER content octets (leading 0x00 when the high bit would make it negative). */
    private fun derIntContent(v: Long): ByteArray {
        if (v == 0L) return byteArrayOf(0)
        var x = v
        val out = ArrayList<Byte>()
        while (x > 0) { out.add(0, (x and 0xFF).toByte()); x = x shr 8 }
        if (out[0].toInt() and 0x80 != 0) out.add(0, 0)
        return out.toByteArray()
    }

    private fun derInt(v: Long): ByteArray = derTlv(0x02, derIntContent(v))

    /**
     * A real TR-03151 transaction LogMessage (ASN.1 DER): ASN.1 version 2 on TSE 1.x, 3 on TSE 2.0.
     * Mirrors the structure the production parser decodes — certifiedData as IMPLICIT context tags
     * [0] operationType [1] clientId [2] processData [3] processType [5] transactionNumber, then
     * serialNumber, an AlgorithmIdentifier SEQUENCE, signatureCounter, signatureCreationTime
     * (unixTime) and signatureValue (the device's synthetic signature).
     */
    private fun buildTransactionLogMessage(
        operationType: String,
        clientId: String,
        processData: ByteArray,
        processType: String,
        transactionNumber: Long,
        sigCounter: Long,
        logTime: Long,
        signature: ByteArray,
    ): ByteArray {
        val body =
            derInt(if (isV2) 3 else 2) +
                derTlv(0x06, oidTransactionLog) +
                derTlv(0x80, operationType.toByteArray()) +
                derTlv(0x81, clientId.toByteArray()) +
                derTlv(0x82, processData) +
                derTlv(0x83, processType.toByteArray()) +
                derTlv(0x85, derIntContent(transactionNumber)) +
                derTlv(0x04, serialNumber) +
                derTlv(0x30, derTlv(0x06, oidEcdsaPlainSha384)) +
                derInt(sigCounter) +
                derInt(logTime) +
                derTlv(0x04, signature)
        return derTlv(0x30, body)
    }

    private fun decodeIncrementalState(state: ByteArray?): Long {
        if (state == null || state.isEmpty()) return 0
        if (state.size != INCREMENTAL_STATE_SIZE) err(WormError.WORM_ERROR_INCREMENTAL_EXPORT_INVALID_STATE)
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (state[i].toLong() and 0xFF)
        return value
    }

    private fun encodeIncrementalState(signatureCounter: Long): ByteArray {
        val state = ByteArray(INCREMENTAL_STATE_SIZE)
        for (i in 0 until 8) state[i] = (signatureCounter ushr ((7 - i) * 8)).toByte()
        return state
    }

    private fun streamTar(tar: ByteArray, sink: (ByteArray) -> Int) {
        var offset = 0
        while (offset < tar.size) {
            val chunk = tar.copyOfRange(offset, minOf(offset + EXPORT_CHUNK_SIZE, tar.size))
            offset += chunk.size
            if (sink(chunk) != 0) err(WormError.WORM_ERROR_EXPORT_FAILED)
        }
    }

    /**
     * Minimal valid USTAR archive: an info.csv plus one file per log message holding its raw DER.
     * This mirrors the real hardware's per-message layout closely enough that the software-side
     * filtered export (which parses each member's DER) can be exercised end to end.
     */
    private fun tarBytes(exportEntries: List<LogEntry>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun member(name: String, content: ByteArray) {
            out.write(tarHeader(name, content.size))
            out.write(content)
            val padding = (TAR_SECTOR_SIZE - content.size % TAR_SECTOR_SIZE) % TAR_SECTOR_SIZE
            if (padding > 0) out.write(ByteArray(padding))
        }
        member("info.csv", "component:,device,serial:,${hex(serialNumber, 8)}\n".toByteArray())
        exportEntries.forEach { entry ->
            member("Transaction_Log-Sig-${entry.id}.log", entry.logMessage)
        }
        out.write(ByteArray(2 * TAR_SECTOR_SIZE)) // two zero blocks terminate the archive
        return out.toByteArray()
    }

    private fun tarHeader(name: String, size: Int): ByteArray {
        val header = ByteArray(TAR_SECTOR_SIZE)
        fun put(offset: Int, text: String) {
            val bytes = text.toByteArray()
            bytes.copyInto(header, offset, 0, bytes.size)
        }
        put(0, name)
        put(100, "0000644 ")
        put(108, "0000000 ")
        put(116, "0000000 ")
        put(124, String.format("%011o", size) + " ")
        put(136, String.format("%011o", 0) + " ")
        put(148, "        ")
        header[156] = '0'.code.toByte()
        put(257, "ustar")
        header[262] = 0
        put(263, "00")
        val checksum = header.sumOf { it.toInt() and 0xFF }
        put(148, String.format("%06o", checksum) + "  ")
        return header
    }

    private fun buildPublicKey(): ByteArray {
        val body = sha256(serialNumber + "public-key".toByteArray()) +
            sha256(serialNumber + "public-key-2".toByteArray()) +
            sha256(serialNumber + "public-key-3".toByteArray())
        return byteArrayOf(0x04) + body
    }

    companion object {
        private const val MAX_LOGIN_RETRIES = 3
        private const val CREDENTIAL_SEED_LENGTH = 16
        private const val ADMIN_PIN_LENGTH = 5
        private const val TIME_ADMIN_PIN_LENGTH = 5
        private const val PUK_LENGTH = 6
        private const val MAX_ENTRIES = 10_000
        private const val MAX_STARTED_TRANSACTIONS = 512L
        private const val MAX_SIGNATURES = 20_000_000L
        private const val MAX_REGISTERED_CLIENTS = 100L
        private const val SELF_TEST_INTERVAL_SECONDS = 90_000L
        private const val TIME_SYNC_INTERVAL_SECONDS = 7_500L
        private const val MAX_UPDATE_DELAY_SECONDS = 45L
        private const val CERTIFICATE_EXPIRATION_UNIX_SECONDS = 1_893_456_000L // 2030-01-01
        private const val CAPACITY_BYTES = 7_000_000_000L
        private const val BASE_USED_BYTES = 1_048_576L
        private const val HARDWARE_VERSION = 65_537L // 1.0.1
        /**
         * TSE *firmware* versions, packed as major<<16 | minor<<8 | patch.
         *
         * Real TSE 1.x hardware reports 1.x.x and TSE 2.0 reports 2.x.x. The previous
         * value here was 5.3.2, which is an SDK version rather than a firmware one and
         * would read as *newer* than a TSE 2.0 — actively misleading for any code that
         * reasons about the generation from this number. Nothing asserts the specific
         * value; `isTSEv2()` remains the supported way to detect the generation.
         */
        /** TSE 2.0 logs out any user after 40 minutes without an authenticated action. */
        private const val AUTO_LOGOUT_MS = 40 * 60 * 1000L

        /**
         * What TSE 1.x reports for the auto-logout timeout: 0xffff, i.e. "not applicable".
         * v1 has no idle logout at all — a session lasts until logout or power cycle.
         */
        private const val V1_NO_AUTO_LOGOUT = 0xFFFF

        /**
         * TSE 2.0 transactions require the Logger user. The SDK sets this PIN itself
         * during setup and logs Logger in automatically; the ERS never learns it, which
         * is why transactions need no stored credential.
         */
        private const val LOGGER_FIXED_PIN = "00000"

        private val FORBIDDEN_CLIENT_ID_CHARS = charArrayOf('/', ':', '?')

        /** TSE 2.0 raises STORE_FULL while this much space is still free. */
        private const val V2_STORE_FULL_RESERVE_BYTES = 1_048_576L

        private const val SOFTWARE_VERSION = 65_539L // 1.0.3
        private const val SOFTWARE_VERSION_V2 = 131_072L // 2.0.0
        private const val TAR_SECTOR_SIZE = 512
        private const val EXPORT_CHUNK_SIZE = 8_192
        private const val INCREMENTAL_STATE_SIZE = 16

        /**
         * Swissbit development samples derive their factory credentials from the
         * well-known seed "SwissbitSwissbit"; with the digit derivation below this
         * yields the documented dev values used across the pos-tse test suite:
         * admin PIN 12345, time-admin PIN 12345, PUK 123456
         * (see MockWormStoreFactory.Defaults).
         */
        private val DEFAULT_SEED_BYTES = "SwissbitSwissbit".toByteArray()

        private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        private fun deriveDigits(seed: ByteArray, offset: Int, length: Int): ByteArray {
            if (seed.contentEquals(DEFAULT_SEED_BYTES)) {
                return when {
                    length == PUK_LENGTH && offset == 16 -> "123456".toByteArray()
                    else -> "12345".toByteArray()
                }
            }
            val digest = sha256(seed + byteArrayOf(offset.toByte()))
            return ByteArray(length) { i -> ('0' + ((digest[i].toInt() and 0x7F) % 10)).code.toByte() }
        }

        private fun longBytes(value: Long): ByteArray = ByteArray(8) { i -> (value ushr ((7 - i) * 8)).toByte() }

        private fun sectors(bytes: Long): Long = (bytes + TAR_SECTOR_SIZE - 1) / TAR_SECTOR_SIZE

        private fun hex(bytes: ByteArray, maxBytes: Int): String =
            bytes.take(maxBytes).joinToString("") { "%02x".format(it) }
    }
}
