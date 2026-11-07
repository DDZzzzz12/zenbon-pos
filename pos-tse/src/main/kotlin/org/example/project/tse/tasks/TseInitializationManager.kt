package org.example.project.tse.tasks

import com.secureflashcard.wormapi.WormAuthenticationFailedException
import com.secureflashcard.wormapi.WormError
import com.secureflashcard.wormapi.WormException
import com.secureflashcard.wormapi.WormInitializationState
import com.secureflashcard.wormapi.WormUserId
import kotlinx.coroutines.delay
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.models.TseStatus
import org.slf4j.LoggerFactory

/**
 * TSE Initialization Manager - Implements the recommended startup sequence.
 *
 * Based on SwissBit TSE documentation and WormStore.java requirements.
 *
 * TSE States:
 * - UNINITIALIZED: Needs tse_setup() before use
 * - INITIALIZED: Ready for normal operations (CTSS enabled)
 * - DECOMMISSIONED: Cannot be used for new transactions
 *
 * Startup Sequence for INITIALIZED TSE:
 * 1. Check initialization state
 * 2. Run self-test (tse_runSelfTest) - no login required
 * 3. Login Admin
 * 4. Check if CTSS is active, enable if needed
 * 5. Update time (tse_updateTime) - requires Admin + CTSS
 * 6. Ready for transactions
 *
 * Key Requirements:
 * - Self-test must execute at least once every 25 hours
 * - Time synchronization required after power cycle
 * - CTSS interface must be active for time updates and transactions
 * - Admin can perform all operations (including time updates)
 *
 * Login State:
 * - Login state is stored on TSE hardware, not in WormStore object
 * - Login persists across WormStore instances until power cycle
 * - After power cycle, all users are logged out
 *
 * @property tseStatus TSE status and configuration manager with WormStore instance
 * @since 2.0.0
 */
class TseInitializationManager(
    private val tseStatus: TseStatus,
) {

    private val logger = LoggerFactory.getLogger(TseInitializationManager::class.java)

    private var adminPin: String? = null
    private var adminPuk: String? = null
    private var clientId: String? = null

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3

        /** Placeholder PINs shipped in the default configuration — never acceptable on a device. */
        private val FACTORY_PLACEHOLDER_PINS = setOf("12345", "23456")

        /** Placeholder PUK shipped in the default configuration. */
        private val FACTORY_PLACEHOLDER_PUKS = setOf("123456")
    }

    /**
     * Bring an already-commissioned TSE up, following the recommended startup sequence.
     *
     * Takes no Admin credential, by design. Everything a restart needs — the self test, the
     * time update — is reachable without Admin, so the ERS has no reason to hold the Admin PIN
     * between sittings (Swissbit guidance manual 2.5 forbids storing it; 2.4 permits the
     * TimeAdmin PIN this path uses). An UNINITIALIZED TSE is therefore *not* commissioned
     * here — that is [setUp], which an operator triggers and supplies credentials for.
     *
     * @return true if the TSE is ready for transactions, false otherwise
     */
    fun initialize(clientId: String?): Boolean =
        tseStatus.withMaintenance("initialization") {
            startFromState(clientId, commissionIfUninitialized = false)
        }

    /**
     * Commission an UNINITIALIZED TSE with operator-supplied credentials, then start it up.
     *
     * Separate from [initialize] because setup is a one-time, operator-present act: it burns
     * the single-use credential seed and binds the serial into the certificate. It must never
     * happen as a side effect of a restart.
     */
    fun setUp(
        adminPin: String,
        adminPuk: String,
        credentialSeed: String,
        clientId: String?,
    ): Boolean = tseStatus.withMaintenance("setup") {
        this.adminPin = adminPin
        this.adminPuk = adminPuk
        tseStatus.setCredentialSeed(credentialSeed)
        startFromState(clientId, commissionIfUninitialized = true)
    }

    private fun startFromState(clientId: String?, commissionIfUninitialized: Boolean): Boolean {
        // In SIMULATION mode, skip hardware initialization
        if (tseStatus.getMode() == TseMode.SIMULATION || tseStatus.getMode() == TseMode.DISABLED) {
            logger.info("TSE running in SIMULATION mode - hardware initialization skipped")
            return true
        }

        if (!tseStatus.hasWormStoreInstance()) {
            logger.error("Cannot initialize: WormStore instance not available")
            logger.error("Call tseStatus.detectHardware() first to establish connection")
            return false
        }

        logger.info("╔════════════════════════════════════════════════════════════════════════════╗")
        logger.info("║                    TSE Initialization Starting                            ║")
        logger.info("╚════════════════════════════════════════════════════════════════════════════╝")

        this.clientId = clientId ?: tseStatus.getClientId()

        // Step 1: Check initialization state
        val initState = checkInitializationState()
        when (initState) {
            WormInitializationState.WORM_INIT_UNINITIALIZED -> {
                if (!commissionIfUninitialized) {
                    logger.error("✗ TSE is UNINITIALIZED — startup cannot commission it")
                    logger.error("  Run TSE setup from the settings screen; an operator must")
                    logger.error("  supply the Admin PIN, PUK and credential seed.")
                    return false
                }
                logger.info("Step 1: TSE is UNINITIALIZED - attempting setup...")

                // Attempt to set up the TSE with the operator-supplied credentials
                if (!performTseSetup()) {
                    logger.error("✗ TSE setup failed - TSE remains UNINITIALIZED")
                    return false
                }
                logger.info("✓ Step 1: TSE setup completed successfully")
            }

            WormInitializationState.WORM_INIT_DECOMMISSIONED -> {
                logger.error("TSE is DECOMMISSIONED. Cannot be used for new transactions.")
                logger.error("A new TSE is required.")
                return false
            }

            WormInitializationState.WORM_INIT_INITIALIZED -> {
                logger.info("✓ Step 1: TSE is INITIALIZED - proceeding with startup sequence")
            }

            null -> {
                logger.warn("Could not determine TSE initialization state - attempting startup anyway")
            }
        }

        // TSE 2.0 takes a minimum-privilege startup instead of the v1 retry loop below.
        // Reached for both a just-set-up device and an already-initialized one.
        if (tseStatus.isTseV2()) {
            return startupV2()
        }

        var attempt = 0
        while (attempt < MAX_RETRY_ATTEMPTS) {
            attempt++
            logger.info("─".repeat(80))
            logger.info("Initialization Attempt: $attempt / $MAX_RETRY_ATTEMPTS")
            logger.info("─".repeat(80))

            try {
                // Step 2: Run self-test (no login required)
                if (!checkSelfTestPassed()) {
                    logger.info("Step 2: Running self-test...")
                    if (!performSelfTest()) {
                        logger.error("✗ Self-test failed")
                        if (tseStatus.getLastAuthFailure() != null) {
                            logger.error("Aborting init: authentication failed during self-test/registration — NOT retrying (PIN-lockout safety)")
                            return false
                        }
                        continue
                    }
                    logger.info("✓ Step 2: Self-test passed")
                } else {
                    logger.info("✓ Step 2: Self-test already passed")
                }

                // Step 3: Login TimeAdmin for the time update.
                //
                // Not Admin. Its only purpose here was the time update — Step 4 below does
                // nothing, because CTSS is already active on an initialised TSE — and
                // tse_updateTime accepts Admin *or* TimeAdmin on both generations. The
                // Swissbit guidance manual (2.4) expects the ERS to hold the TimeAdmin PIN
                // precisely because the time needs setting several times a day, while 2.5
                // forbids storing the Admin PIN. Requiring Admin here would mean every
                // restart needed a credential the ERS is not permitted to keep.
                logger.info("Step 3: Logging in as TimeAdmin...")
                if (!loginTimeAdmin()) {
                    logger.error("✗ TimeAdmin login failed")
                    if (tseStatus.getLastAuthFailure() != null) {
                        logger.error(
                            "Aborting init: TimeAdmin authentication failed (remainingRetries=" +
                                "${tseStatus.getLastAuthFailure()?.remainingRetries}, blocked=" +
                                "${tseStatus.getLastAuthFailure()?.pinBlocked}) — NOT retrying (PIN-lockout safety)",
                        )
                        return false
                    }
                    continue
                }
                logger.info("✓ Step 3: TimeAdmin logged in")

                // Step 4: Ensure CTSS is active (should be for INITIALIZED TSE)
                // For INITIALIZED TSE, CTSS should already be enabled
                // We attempt operations and handle CTSS errors if they occur

                // Step 5: Update time if needed
                if (!checkTimeValid()) {
                    logger.info("Step 5: Updating time...")
                    if (!performTimeUpdate()) {
                        logger.error("✗ Time update failed")
                        continue
                    }
                    logger.info("✓ Step 5: Time updated successfully")
                } else {
                    logger.info("✓ Step 5: Time is already valid")
                }

                // Log TimeAdmin out again. Harmless on v1, where sessions are independent and
                // long-lived, but it keeps the resting state identical on both generations —
                // and on TSE 2.0 a resting TimeAdmin blocks every export and entry read.
                tseStatus.executeOnTseThread {
                    runCatching { tseStatus.getWormStore().user_logout(WormUserId.WORM_USER_TIME_ADMIN) }
                }

                // Final verification
                if (checkSelfTestPassed() && checkTimeValid()) {
                    logger.info("╔════════════════════════════════════════════════════════════════════════════╗")
                    logger.info("║                    TSE Initialization COMPLETE                            ║")
                    logger.info("╚════════════════════════════════════════════════════════════════════════════╝")
                    logger.info("TSE is ready for transaction signing")
                    return true
                }

            } catch (e: Exception) {
                logger.error("Initialization attempt $attempt failed: ${e.message}", e)
            }

            if (attempt < MAX_RETRY_ATTEMPTS) {
                logger.warn("Retrying initialization...")
                Thread.sleep(1000)
            }
        }

        logger.error("╔════════════════════════════════════════════════════════════════════════════╗")
        logger.error("║                    TSE Initialization FAILED                              ║")
        logger.error("╚════════════════════════════════════════════════════════════════════════════╝")
        logger.error("Failed after $MAX_RETRY_ATTEMPTS attempts")
        return false
    }

    /**
     * Check TSE initialization state.
     *
     * Returns the WormInitializationState directly from the TSE hardware.
     * Possible values:
     * - WORM_INIT_UNINITIALIZED: TSE needs tse_setup() before use
     * - WORM_INIT_INITIALIZED: TSE is ready for normal operations
     * - WORM_INIT_DECOMMISSIONED: TSE cannot be used for new transactions
     *
     * @return WormInitializationState from WormAPI, or null if unable to determine
     */
    private fun checkInitializationState(): WormInitializationState? = tseStatus.executeOnTseThread {
        try {
            val store = tseStatus.getWormStore()
            store.info().use { info ->
                val state = info.initializationState()
                logger.debug("TSE initialization state: $state")
                state
            }
        } catch (e: Exception) {
            logger.error("Failed to check initialization state: ${e.message}", e)
            null
        }
    }

    /**
     * Check if TSE has passed self-test.
     *
     * @return true if self-test passed, false otherwise
     */
    private fun checkSelfTestPassed(): Boolean = tseStatus.executeOnTseThread {
        try {
            val store = tseStatus.getWormStore()
            store.info().use { info ->
                val passed = info.hasPassedSelfTest()
                logger.debug("Self-test status: ${if (passed) "PASSED" else "NOT PASSED"}")
                if (passed) {
                    val timeUntilNext = info.timeUntilNextSelfTest()
                    logger.debug("Time until next self-test required: ${timeUntilNext}s")
                }
                passed
            }
        } catch (e: Exception) {
            logger.error("Failed to check self-test status: ${e.message}", e)
            false
        }
    }

    /**
     * Check if TSE has valid time.
     *
     * @return true if time is valid, false otherwise
     */
    private fun checkTimeValid(): Boolean = tseStatus.executeOnTseThread {
        try {
            val store = tseStatus.getWormStore()
            store.info().use { info ->
                val valid = info.hasValidTime()
                logger.debug("Time validity: ${if (valid) "VALID" else "INVALID"}")
                if (valid) {
                    val timeUntilSync = info.timeUntilNextTimeSynchronization()
                    logger.debug("Time until next sync required: ${timeUntilSync}s")
                }
                valid
            }
        } catch (e: Exception) {
            logger.error("Failed to check time validity: ${e.message}", e)
            false
        }
    }

    /**
     * Perform TSE self-test.
     *
     * Self-test does NOT require login. If client is not registered,
     * will attempt to register it (which requires Admin login).
     *
     * @return true if self-test successful, false otherwise
     */
    private fun performSelfTest(): Boolean = tseStatus.executeOnTseThread {
        val store = tseStatus.getWormStore()
        val clientId = tseStatus.getClientId()

        // TSE 1.x needs a registered client for the self test; TSE 2.0 ignores the parameter.
        // Fail with something diagnosable rather than passing null into the SDK.
        if (clientId == null && !tseStatus.isTseV2()) {
            logger.error(
                "Cannot run self-test: no client ID configured. The TSE is not commissioned " +
                    "for this register.",
            )
            return@executeOnTseThread false
        }

        try {
            logger.info("Running TSE self-test (may take up to 60 seconds)...")
            val startTime = System.currentTimeMillis()

            tseStatus.runSelfTestOnDevice(clientId)

            val duration = System.currentTimeMillis() - startTime
            logger.info("Self-test completed in ${duration}ms")
            true
        } catch (e: WormException) {
            val errorMsg = e.message ?: ""
            val errorCode = e.error().toString()
            logger.error("Self-test failed: $errorMsg (code: $errorCode)")

            // Deliberately no registration attempt here any more.
            //
            // It logged in as Admin and registered the client, which is wrong three times
            // over: registering a Kasse declares which register this TSE signs for and is an
            // operator-present act (see the terminal-pairing design, and the matching removals
            // from ApplyConfigFromMainServer and TseMaintenanceTask); startup no longer holds
            // an Admin PIN, so the `!!` it used threw a NullPointerException that the retry
            // loop silently swallowed and repeated; and a Kasse the TSE has never heard of is
            // a configuration error worth surfacing, not papering over.
            if (errorMsg.contains("CLIENT_NOT_REGISTERED", ignoreCase = true) ||
                errorCode.contains("CLIENT_NOT_REGISTERED", ignoreCase = true)
            ) {
                logger.error(
                    "✗ This register ('$clientId') is not registered on the TSE. Register it " +
                        "from the settings screen — an operator must confirm which Kasse this " +
                        "TSE signs for.",
                )
            }

            false
        } catch (e: Exception) {
            logger.error("Unexpected error during self-test: ${e.message}", e)
            false
        }
    }

    /**
     * Login as TimeAdmin, for the startup time update.
     *
     * Records the auth failure so the caller's abort-without-retry PIN-lockout guard still
     * applies. TimeAdmin has its
     * own retry counter on the TSE, so burning attempts here is as damaging as with Admin.
     */
    private fun loginTimeAdmin(): Boolean = tseStatus.executeOnTseThread {
        val store = tseStatus.getWormStore()
        val pin = tseStatus.getTimeAdminPin().toByteArray(Charsets.UTF_8)

        try {
            store.user_login(WormUserId.WORM_USER_TIME_ADMIN, pin)
            logger.debug("TimeAdmin login successful")
            true
        } catch (e: WormAuthenticationFailedException) {
            tseStatus.recordAuthFailure(
                TseDto.TseAuthFailureInfo(
                    remainingRetries = e.remainingRetries,
                    pinBlocked = e.error() == WormError.WORM_ERROR_AUTHENTICATION_PIN_BLOCKED,
                )
            )
            logger.error("TimeAdmin login authentication failed (remainingRetries=${e.remainingRetries}, code=${e.error()})")
            false
        } catch (e: WormException) {
            val errorMsg = e.message ?: ""
            if (errorMsg.contains("ALREADY", ignoreCase = true) ||
                errorMsg.contains("LOGGED_IN", ignoreCase = true)
            ) {
                logger.debug("TimeAdmin already logged in")
                return@executeOnTseThread true
            }
            if (e.error() == WormError.WORM_ERROR_AUTHENTICATION_PIN_BLOCKED) {
                tseStatus.recordAuthFailure(TseDto.TseAuthFailureInfo(remainingRetries = 0, pinBlocked = true))
            }
            logger.error("TimeAdmin login failed: $errorMsg (code: ${e.error()})")
            false
        } catch (e: Exception) {
            logger.error("Unexpected error during TimeAdmin login: ${e.message}", e)
            false
        }
    }

    /**
     * Perform time update.
     *
     * Requires Admin to be logged in and CTSS interface to be active.
     * For INITIALIZED TSE, CTSS should already be enabled.
     *
     * @return true if time update successful, false otherwise
     */
    private fun performTimeUpdate(): Boolean = tseStatus.executeOnTseThread {
        val store = tseStatus.getWormStore()

        try {
            val currentTime = System.currentTimeMillis() / 1000
            logger.debug("Updating TSE time to: $currentTime")

            store.tse_updateTime(currentTime)

            logger.info("Time updated: $currentTime (${java.util.Date(currentTime * 1000)})")
            true
        } catch (e: WormException) {
            val errorMsg = e.message ?: ""
            val errorCode = e.error().toString()
            logger.error("Time update failed: $errorMsg (code: $errorCode)")

            // Check for CTSS interface error
            if (errorMsg.contains("CTSS", ignoreCase = true) ||
                errorCode.contains("CTSS", ignoreCase = true)
            ) {
                logger.error("CTSS interface may not be active. For INITIALIZED TSE, this should not happen.")
                logger.error("Try calling tse_ctss_enable() if CTSS was manually disabled.")
            }

            // Check for self-test error
            if (errorMsg.contains("SELF_TEST", ignoreCase = true) ||
                errorCode.contains("SELF_TEST", ignoreCase = true)
            ) {
                logger.warn("Self-test required - will retry in next iteration")
            }

            false
        } catch (e: Exception) {
            logger.error("Unexpected error during time update: ${e.message}", e)
            false
        }
    }

    /**
     * Data class holding TSE credential change state.
     *
     * Used to determine the correct initialization path:
     * - All false: Fresh TSE, safe to call tse_setup()
     * - Any true + UNINITIALIZED: Partially initialized, need manual steps
     * - All true + INITIALIZED: Already set up, skip setup
     */
    private data class CredentialChangeState(
        val hasChangedAdminPin: Boolean,
        val hasChangedPuk: Boolean,
        val hasChangedTimeAdminPin: Boolean
    ) {
        val anyCredentialsChanged: Boolean
            get() = hasChangedAdminPin || hasChangedPuk || hasChangedTimeAdminPin

        val allCredentialsChanged: Boolean
            get() = hasChangedAdminPin && hasChangedPuk && hasChangedTimeAdminPin
    }

    /**
     * Check if TSE credentials (PIN/PUK) have already been changed.
     *
     * CRITICAL for avoiding TSE bricking:
     * - If credentials were changed but TSE is still UNINITIALIZED,
     *   calling tse_setup() will try to use initial credentials which are no longer valid.
     * - After 3 failed attempts, PUK gets blocked and TSE is permanently bricked.
     *
     * @return CredentialChangeState with current PIN/PUK change status
     */
    private fun checkCredentialChangeState(): CredentialChangeState? = tseStatus.executeOnTseThread {
        try {
            val store = tseStatus.getWormStore()
            store.info().use { info ->
                val state = CredentialChangeState(
                    hasChangedAdminPin = info.hasChangedAdminPin(),
                    hasChangedPuk = info.hasChangedPuk(),
                    hasChangedTimeAdminPin = info.hasChangedTimeAdminPin()
                )
                logger.info("Credential change state:")
                logger.info("  - Admin PIN changed: ${state.hasChangedAdminPin}")
                logger.info("  - PUK changed: ${state.hasChangedPuk}")
                logger.info("  - TimeAdmin PIN changed: ${state.hasChangedTimeAdminPin}")
                state
            }
        } catch (e: Exception) {
            logger.error("Failed to check credential change state: ${e.message}", e)
            null
        }
    }

    /**
     * Perform TSE setup for UNINITIALIZED TSE.
     *
     * IMPORTANT: This method first checks if PIN/PUK have already been changed.
     * If they have, it performs manual initialization instead of tse_setup()
     * to avoid bricking the TSE.
     *
     * Two initialization paths:
     *
     * 1. FRESH TSE (no credentials changed):
     *    - Call tse_setup() which handles everything automatically
     *
     * 2. PARTIALLY INITIALIZED TSE (some credentials already changed):
     *    - Login with already-changed Admin PIN
     *    - Register client
     *    - Run self-test
     *    - Enable CTSS
     *    - Initialize TSE
     *
     * @return true if setup successful, false otherwise
     */
    private fun performTseSetup(): Boolean {
        // CRITICAL: Check credential state BEFORE attempting setup
        val credState = checkCredentialChangeState()
        if (credState == null) {
            logger.error("Cannot determine credential state - aborting setup for safety")
            return false
        }

        if (credState.anyCredentialsChanged) {
            logger.warn("╔════════════════════════════════════════════════════════════════════════════╗")
            logger.warn("║  NOTE: Some credentials already changed - resuming partial setup          ║")
            logger.warn("╚════════════════════════════════════════════════════════════════════════════╝")
        }

        return performTseSetupWithState(credState)
    }

    /**
     * Perform TSE setup using individual WormStore method calls.
     *
     * Unified method that handles both fresh TSE setup and partial setup recovery.
     * Uses credState to determine which steps need to be performed:
     * - If credential already changed: skip that credential change step
     * - If credential NOT changed: change it from initial to configured value
     *
     * Steps:
     * 1. Derive initial credentials from seed
     * 2. Change PUK (if not already changed)
     * 3. Change Admin PIN (if not already changed)
     * 4. Change TimeAdmin PIN (if not already changed)
     * 5. Login as Admin
     * 6. Register the first client
     * 7. Run self-test
     * 8. Enable CTSS interface
     * 9. Enable export if CSP test fails
     * 10. Initialize the TSE
     *
     * @param credState Current credential change state from TSE
     * @return true if setup successful, false otherwise
     */
    private fun performTseSetupWithState(credState: CredentialChangeState): Boolean {
        val store = tseStatus.getWormStore()

        // Check the inputs before touching the device. Everything below this point is either
        // irreversible (user_change_puk, user_change_pin) or spends the single-use credential
        // seed, so a missing value has to stop us here rather than halfway through. Without
        // this the failure mode is a NullPointerException from the `!!`s below — or, on the
        // v1 pre-step, from the SWIG wrapper, which rejects a null string before the SDK sees
        // it and so is not caught by the `catch (WormException)` around it.
        val missing = buildList {
            if (clientId.isNullOrBlank()) add("client ID")
            if (adminPin.isNullOrBlank()) add("Admin PIN")
            if (adminPuk.isNullOrBlank()) add("Admin PUK")
            if (tseStatus.getCredentialSeed().isBlank()) add("credential seed")
            if (tseStatus.getTimeAdminPin().isBlank()) add("TimeAdmin PIN")
        }
        if (missing.isNotEmpty()) {
            logger.error("✗ Cannot set up the TSE: missing ${missing.joinToString(", ")}")
            logger.error("  Nothing has been written to the device.")
            return false
        }

        // Refuse the shipped placeholders. Setup writes these into the device one-way, so a
        // PIN nobody chose is permanent — and the TimeAdmin PIN is the one credential the ERS
        // is allowed to keep on disk, which makes it precisely the one worth choosing. This is
        // the check hasConfiguredCredentials() was meant to be before it fell out of use.
        val placeholders = buildList {
            if (adminPin in FACTORY_PLACEHOLDER_PINS) add("Admin PIN")
            if (adminPuk in FACTORY_PLACEHOLDER_PUKS) add("Admin PUK")
            if (tseStatus.getTimeAdminPin() in FACTORY_PLACEHOLDER_PINS) add("TimeAdmin PIN")
        }
        if (placeholders.isNotEmpty()) {
            logger.error(
                "✗ Cannot set up the TSE: ${placeholders.joinToString(", ")} still set to a " +
                    "factory placeholder. Choose real values — setup writes them to the device " +
                    "one-way and they cannot be changed back.",
            )
            logger.error("  Nothing has been written to the device.")
            return false
        }

        // The preliminary self-test belongs to the certified v1 sequence only. On v2 the
        // self test requires an already-initialized TSE, so it would always fail here, and
        // the hardware runs it automatically anyway.
        if (!tseStatus.isTseV2()) tseStatus.executeOnTseThread {
            // ════════════════════════════════════════════════════════════════════════════
            // Pre-Step: Run self-test first (required before setup can proceed)
            // ════════════════════════════════════════════════════════════════════════════
            logger.info("[Pre-Step] Running preliminary self-test (required before setup)...")
            val preSelfTestStart = System.currentTimeMillis()
            try {
                tseStatus.runSelfTestOnDevice(clientId)
                logger.info("✓ [Pre-Step] Preliminary self-test completed in ${System.currentTimeMillis() - preSelfTestStart}ms")
            } catch (e: WormException) {
                val errorMsg = e.message ?: ""
                // CLIENT_NOT_REGISTERED is expected for UNINITIALIZED TSE - will be handled after client registration
                if (errorMsg.contains("CLIENT_NOT_REGISTERED", ignoreCase = true)) {
                    logger.info("  [Pre-Step] Client not registered yet (expected for fresh TSE) - will run after registration")
                } else {
                    logger.warn("⚠ [Pre-Step] Preliminary self-test note: $errorMsg - continuing with setup")
                }
            }
            logger.info("─".repeat(80))
        }

        return tseStatus.executeOnTseThread {
            if (tseStatus.isTseV2()) {
                return@executeOnTseThread setupViaVendorHelper(store, credState)
            }

            val clientId = this.clientId!!
            val newAdminPuk = this.adminPuk!!.toByteArray(Charsets.UTF_8)
            val newAdminPin = this.adminPin!!.toByteArray(Charsets.UTF_8)
            val newTimeAdminPin = tseStatus.getTimeAdminPin()
            val credentialSeed = tseStatus.getCredentialSeed().toByteArray(Charsets.UTF_8)

            val totalSteps = 10
            var currentStep = 0
            val startTime = System.currentTimeMillis()

            val isFreshSetup = !credState.anyCredentialsChanged
            val setupType = if (isFreshSetup) "Fresh Initialization" else "Partial Setup Recovery"

            try {
            logger.info("╔════════════════════════════════════════════════════════════════════════════╗")
            logger.info("║              TSE SETUP - $setupType".padEnd(79) + "║")
            logger.info("╚════════════════════════════════════════════════════════════════════════════╝")
            logger.info("Configuration:")
            logger.info("  - Credential Seed: ${if (credentialSeed.size > 8) "***configured***" else "***short/default***"}")
            logger.info("  - Client ID: $clientId")
            logger.info("  - Admin PUK: ******")
            logger.info("  - Admin PIN: ******")
            logger.info("  - Time Admin PIN: ******")
            logger.info("Credential State:")
            logger.info("  - PUK changed: ${credState.hasChangedPuk}")
            logger.info("  - Admin PIN changed: ${credState.hasChangedAdminPin}")
            logger.info("  - TimeAdmin PIN changed: ${credState.hasChangedTimeAdminPin}")
            logger.info("─".repeat(80))

            // ════════════════════════════════════════════════════════════════════════════
            // Step 1: Derive initial credentials from seed
            // ════════════════════════════════════════════════════════════════════════════
            currentStep++
            logger.info("[$currentStep/$totalSteps] Deriving initial credentials from seed...")
            val stepStart = System.currentTimeMillis()

            val initialCreds = try {
                store.user_deriveInitialCredentials(credentialSeed)
            } catch (e: WormException) {
                logger.error("✗ [$currentStep/$totalSteps] Failed to derive initial credentials: ${e.message} (code: ${e.error()})")
                return@executeOnTseThread false
            }

            initialCreds.use { creds ->
                logger.info("✓ [$currentStep/$totalSteps] Initial credentials derived in ${System.currentTimeMillis() - stepStart}ms")
                logger.debug("  - Initial Admin PUK length: ${creds.adminPuk().size}")
                logger.debug("  - Initial Admin PIN length: ${creds.adminPin().size}")
                logger.debug("  - Initial TimeAdmin PIN length: ${creds.timeAdminPin().size}")

                val initialAdminPuk = creds.adminPuk()
                val initialAdminPin = creds.adminPin()
                val initialTimeAdminPin = creds.timeAdminPin()

                // ════════════════════════════════════════════════════════════════════════════
                // Step 2: Change PUK (if not already changed)
                // ════════════════════════════════════════════════════════════════════════════
                //
                // This is the first credential operation the device permits, and it is
                // unavoidably where a wrong seed is discovered: `user_login` is forbidden until
                // the PUK has been changed (WormStore.user_login: "Before any user can be logged
                // in, the initial PUK must have been changed"), so there is no cheaper check to
                // run first. A seed of the right length for the wrong device derives a wrong PUK,
                // and this call then spends one of the device's PUK attempts rejecting it.
                //
                // So the guard is: fail cleanly, say plainly that the seed is the suspect, and
                // do not retry — the caller must not turn one operator typo into a blocked PUK.
                currentStep++
                if (!credState.hasChangedPuk) {
                    logger.info("[$currentStep/$totalSteps] Changing PUK from initial to configured value...")
                    val step2Start = System.currentTimeMillis()
                    try {
                        store.user_change_puk(initialAdminPuk, newAdminPuk)
                        logger.info("✓ [$currentStep/$totalSteps] PUK changed successfully in ${System.currentTimeMillis() - step2Start}ms")
                    } catch (e: WormAuthenticationFailedException) {
                        // Authentication failed means the derived PUK was wrong, which means the
                        // seed was wrong — the device's own credentials cannot be "wrong".
                        tseStatus.recordAuthFailure(
                            TseDto.TseAuthFailureInfo(
                                remainingRetries = e.remainingRetries,
                                pinBlocked = e.error() == WormError.WORM_ERROR_AUTHENTICATION_PIN_BLOCKED,
                            )
                        )
                        logger.error(
                            "✗ The credential seed does not match this TSE — the derived PUK was " +
                                "rejected (remainingRetries=${e.remainingRetries}). Nothing else " +
                                "has been changed. Check the seed printed with the device; each " +
                                "wrong attempt spends a PUK try, and exhausting them is permanent.",
                        )
                        return@executeOnTseThread false
                    } catch (e: WormException) {
                        logger.error("✗ [$currentStep/$totalSteps] PUK change failed: ${e.message} (code: ${e.error()})")
                        return@executeOnTseThread false
                    }
                } else {
                    logger.info("✓ [$currentStep/$totalSteps] PUK already changed (skipped)")
                }

                // ════════════════════════════════════════════════════════════════════════════
                // Step 3: Change Admin PIN (if not already changed)
                // ════════════════════════════════════════════════════════════════════════════
                currentStep++
                if (!credState.hasChangedAdminPin) {
                    logger.info("[$currentStep/$totalSteps] Changing Admin PIN from initial to configured value...")
                    logger.debug("  - Initial Admin PIN length: ${initialAdminPin.size} bytes")
                    logger.debug("  - New Admin PIN length: ${newAdminPin.size} bytes")
                    logger.debug("  - PINs are identical: ${initialAdminPin.contentEquals(newAdminPin)}")
                    val step3Start = System.currentTimeMillis()
                    try {
                        store.user_login(WormUserId.WORM_USER_ADMIN, initialAdminPin)
                        store.user_change_pin(WormUserId.WORM_USER_ADMIN, initialAdminPin, newAdminPin)
                        logger.info("✓ [$currentStep/$totalSteps] Admin PIN changed successfully in ${System.currentTimeMillis() - step3Start}ms")
                    } catch (e: WormException) {
                        logger.error("✗ [$currentStep/$totalSteps] Admin PIN change failed: ${e.message} (code: ${e.error()})")
                        logger.error("  Diagnostics:")
                        logger.error("    - Initial Admin PIN length: ${initialAdminPin.size} bytes")
                        logger.error("    - New Admin PIN length: ${newAdminPin.size} bytes")
                        logger.error("    - PINs are identical: ${initialAdminPin.contentEquals(newAdminPin)}")
                        logger.error("    - credState.hasChangedAdminPin: ${credState.hasChangedAdminPin}")
                        logger.error("  Possible causes of INVALID_PARAMETER:")
                        logger.error("    1. Admin PIN was already changed (TSE state inconsistent)")
                        logger.error("    2. Credential seed doesn't match TSE's actual seed")
                        logger.error("    3. PIN length invalid (must be 5-8 ASCII chars)")
                        logger.error("    4. New PIN same as current (PINs must differ)")
                        logger.error("  Suggested fix: Try factory reset if TSE is in development mode")
                        return@executeOnTseThread false
                    }
                } else {
                    logger.info("✓ [$currentStep/$totalSteps] Admin PIN already changed (skipped)")
                }

                // ════════════════════════════════════════════════════════════════════════════
                // Step 4: Change TimeAdmin PIN (if not already changed)
                // ════════════════════════════════════════════════════════════════════════════
                currentStep++
                if (!credState.hasChangedTimeAdminPin) {
                    logger.info("[$currentStep/$totalSteps] Changing TimeAdmin PIN from initial to configured value...")
                    val step4Start = System.currentTimeMillis()
                    try {
                        store.user_login(WormUserId.WORM_USER_TIME_ADMIN, initialTimeAdminPin)
                        store.user_change_pin(
                            WormUserId.WORM_USER_TIME_ADMIN,
                            initialTimeAdminPin,
                            newTimeAdminPin.toByteArray(Charsets.UTF_8)
                        )
                        logger.info("✓ [$currentStep/$totalSteps] TimeAdmin PIN changed successfully in ${System.currentTimeMillis() - step4Start}ms")
                    } catch (e: WormException) {
                        logger.error("✗ [$currentStep/$totalSteps] TimeAdmin PIN change failed: ${e.message} (code: ${e.error()})")
                        return@executeOnTseThread false
                    }
                } else {
                    logger.info("✓ [$currentStep/$totalSteps] TimeAdmin PIN already changed (skipped)")
                }
            }

            // ════════════════════════════════════════════════════════════════════════════
            // Step 5: Login as Admin
            // ════════════════════════════════════════════════════════════════════════════
            currentStep++
            logger.info("[$currentStep/$totalSteps] Logging in as Admin...")
            val step5Start = System.currentTimeMillis()
            try {
                store.user_login(WormUserId.WORM_USER_ADMIN, newAdminPin)
                logger.info("✓ [$currentStep/$totalSteps] Admin login successful in ${System.currentTimeMillis() - step5Start}ms")
            } catch (e: WormException) {
                val errorMsg = e.message ?: ""
                if (errorMsg.contains("ALREADY", ignoreCase = true) ||
                    errorMsg.contains("LOGGED_IN", ignoreCase = true)
                ) {
                    logger.info("✓ [$currentStep/$totalSteps] Admin already logged in (skipped)")
                } else {
                    logger.error("✗ [$currentStep/$totalSteps] Admin login failed: $errorMsg (code: ${e.error()})")
                    return@executeOnTseThread false
                }
            }

            // ════════════════════════════════════════════════════════════════════════════
            // Step 6: Register the first client
            // ════════════════════════════════════════════════════════════════════════════
            currentStep++
            logger.info("[$currentStep/$totalSteps] Registering client '$clientId'...")
            val step6Start = System.currentTimeMillis()

            try {
                store.tse_registerClient(clientId)
                logger.info("✓ [$currentStep/$totalSteps] Client '$clientId' registered in ${System.currentTimeMillis() - step6Start}ms")
            } catch (e: WormException) {
                val errorMsg = e.message ?: ""
                if (errorMsg.contains("ALREADY", ignoreCase = true) ||
                    errorMsg.contains("REGISTERED", ignoreCase = true)
                ) {
                    logger.info("✓ [$currentStep/$totalSteps] Client already registered (skipped)")
                } else {
                    logger.error("✗ [$currentStep/$totalSteps] Client registration failed: $errorMsg (code: ${e.error()})")
                    return@executeOnTseThread false
                }
            }

            // ════════════════════════════════════════════════════════════════════════════
            // Step 7: Run self-test
            // ════════════════════════════════════════════════════════════════════════════
            currentStep++
            logger.info("[$currentStep/$totalSteps] Running self-test (may take up to 60 seconds)...")
            val step7Start = System.currentTimeMillis()

            try {
                tseStatus.runSelfTestOnDevice(clientId)
                logger.info("✓ [$currentStep/$totalSteps] Self-test passed in ${System.currentTimeMillis() - step7Start}ms")
            } catch (e: WormException) {
                logger.error("✗ [$currentStep/$totalSteps] Self-test failed: ${e.message} (code: ${e.error()})")
                return@executeOnTseThread false
            }

            // ════════════════════════════════════════════════════════════════════════════
            // Step 8: Enable CTSS interface
            // ════════════════════════════════════════════════════════════════════════════
            currentStep++
            logger.info("[$currentStep/$totalSteps] Enabling CTSS interface...")
            val step8Start = System.currentTimeMillis()

            try {
                store.tse_ctss_enable()
                logger.info("✓ [$currentStep/$totalSteps] CTSS interface enabled in ${System.currentTimeMillis() - step8Start}ms")
            } catch (e: WormException) {
                val errorMsg = e.message ?: ""
                if (errorMsg.contains("ALREADY", ignoreCase = true) ||
                    errorMsg.contains("ENABLED", ignoreCase = true)
                ) {
                    logger.info("✓ [$currentStep/$totalSteps] CTSS already enabled (skipped)")
                } else {
                    logger.error("✗ [$currentStep/$totalSteps] CTSS enable failed: $errorMsg (code: ${e.error()})")
                    return@executeOnTseThread false
                }
            }

            // ════════════════════════════════════════════════════════════════════════════
            // Step 9: Enable export if CSP test fails
            // ════════════════════════════════════════════════════════════════════════════
            currentStep++
            logger.info("[$currentStep/$totalSteps] Enabling export if CSP test fails...")
            val step9Start = System.currentTimeMillis()

            try {
                store.tse_enableExportIfCspTestFails()
                logger.info("✓ [$currentStep/$totalSteps] Export-on-CSP-fail enabled in ${System.currentTimeMillis() - step9Start}ms")
            } catch (e: WormException) {
                val errorMsg = e.message ?: ""
                if (errorMsg.contains("ALREADY", ignoreCase = true)) {
                    logger.info("✓ [$currentStep/$totalSteps] Export-on-CSP-fail already enabled (skipped)")
                } else {
                    // This is not critical, log warning but continue
                    logger.warn("⚠ [$currentStep/$totalSteps] Export-on-CSP-fail enable failed: $errorMsg (non-critical)")
                }
            }

            // ════════════════════════════════════════════════════════════════════════════
            // Step 10: Initialize the TSE
            // ════════════════════════════════════════════════════════════════════════════
            currentStep++
            logger.info("[$currentStep/$totalSteps] Initializing TSE...")
            val step10Start = System.currentTimeMillis()

            try {
                store.tse_initialize()
                logger.info("✓ [$currentStep/$totalSteps] TSE initialized in ${System.currentTimeMillis() - step10Start}ms")
            } catch (e: WormException) {
                val errorMsg = e.message ?: ""
                if (errorMsg.contains("ALREADY", ignoreCase = true) ||
                    errorMsg.contains("INITIALIZED", ignoreCase = true)
                ) {
                    logger.info("✓ [$currentStep/$totalSteps] TSE already initialized (skipped)")
                } else {
                    logger.error("✗ [$currentStep/$totalSteps] TSE initialization failed: $errorMsg (code: ${e.error()})")
                    return@executeOnTseThread false
                }
            }


            // ════════════════════════════════════════════════════════════════════════════
            // Setup Complete
            // ════════════════════════════════════════════════════════════════════════════
            val totalDuration = System.currentTimeMillis() - startTime
            logger.info("─".repeat(80))
            logger.info("╔════════════════════════════════════════════════════════════════════════════╗")
            logger.info("║                    TSE SETUP COMPLETED SUCCESSFULLY                        ║")
            logger.info("╚════════════════════════════════════════════════════════════════════════════╝")
            logger.info("Total setup time: ${totalDuration}ms")
            logger.info("TSE is now INITIALIZED with:")
            logger.info("  ✓ Initial credentials derived from seed")
            logger.info("  ✓ PUK ${if (credState.hasChangedPuk) "was already" else "now"} changed")
            logger.info("  ✓ Admin PIN ${if (credState.hasChangedAdminPin) "was already" else "now"} changed")
            logger.info("  ✓ TimeAdmin PIN ${if (credState.hasChangedTimeAdminPin) "was already" else "now"} changed")
            logger.info("  ✓ Admin logged in")
            logger.info("  ✓ Client '$clientId' registered")
            logger.info("  ✓ Self-test passed")
            logger.info("  ✓ CTSS interface enabled")
            logger.info("  ✓ Export-on-CSP-fail enabled")
            logger.info("  ✓ TSE initialized and ready for transactions")

            // Update cached initialization state
            val finalState = store.info().use { it.initializationState() }
            tseStatus.setInitializationState(finalState)
            logger.info("  ✓ Initialization state updated to: $finalState")

            finalState == WormInitializationState.WORM_INIT_INITIALIZED

        } catch (e: WormException) {
            val errorMsg = e.message ?: ""
            val errorCode = e.error().toString()
            logger.error("─".repeat(80))
            logger.error("TSE setup failed at step $currentStep/$totalSteps: $errorMsg (code: $errorCode)")

            when {
                errorMsg.contains("AUTHENTICATION", ignoreCase = true) ||
                        errorMsg.contains("PIN", ignoreCase = true) ||
                        errorMsg.contains("PUK", ignoreCase = true) -> {
                    logger.error("Authentication error during setup. Check credential seed and PINs.")
                    logger.error("For development samples, seed should be 'SwissbitSwissbit'")
                }

                errorMsg.contains("WRONG_STATE", ignoreCase = true) -> {
                    logger.error("TSE is not in correct state for this operation.")
                }

                else -> {
                    logger.error("Setup failed with unexpected error.")
                }
            }
            false
        } catch (e: Exception) {
            logger.error("─".repeat(80))
            logger.error("Unexpected error during TSE setup at step $currentStep/$totalSteps: ${e.message}", e)
            false
        }
        }
    }

    /**
     * Bring a TSE 2.0 to a transaction-ready state without any Admin credential.
     *
     * Deliberately not the SDK's `tse_startup`: that helper takes the Admin PIN purely to
     * update the time, which *TimeAdmin* can do — and the Swissbit guidance manual (2.5)
     * forbids the ERS from storing the Admin PIN while explicitly permitting the TimeAdmin
     * one. Transactions themselves need no credential at all, because the SDK logs the
     * Logger user in by itself using a PIN it set during setup.
     *
     * The final logout is mandatory and lives in a `finally`: TimeAdmin may neither export
     * nor read entries on v2, so a half-finished startup must not strand it logged in.
     * Ending with nobody logged in is the correct resting state — the next transaction
     * makes the SDK log Logger in automatically.
     */
    private fun startupV2(): Boolean = tseStatus.executeOnTseThread {
        val store = tseStatus.getWormStore()
        logger.info("TSE 2.0 startup (minimum privilege — no Admin credential)...")

        try {
            // Needs no login on v2 and only excludes TimeAdmin, who is not logged in here.
            tseStatus.runSelfTestOnDevice(this.clientId ?: tseStatus.getClientId())
            logger.info("✓ Self-test passed")
        } catch (e: WormException) {
            logger.error("✗ Self-test failed: ${e.message} (code: ${e.error()})")
            return@executeOnTseThread false
        }

        try {
            val needsUpdate = store.info().use { !it.hasValidTime() }
            if (needsUpdate) {
                store.user_login(
                    WormUserId.WORM_USER_TIME_ADMIN,
                    tseStatus.getTimeAdminPin().toByteArray(Charsets.UTF_8),
                )
                store.tse_updateTime(System.currentTimeMillis() / 1000)
                logger.info("✓ Time updated")
            } else {
                logger.info("✓ Time already valid")
            }
        } catch (e: WormAuthenticationFailedException) {
            tseStatus.recordAuthFailure(
                TseDto.TseAuthFailureInfo(
                    remainingRetries = e.remainingRetries,
                    pinBlocked = e.error() == WormError.WORM_ERROR_AUTHENTICATION_PIN_BLOCKED,
                )
            )
            logger.error(
                "✗ TimeAdmin authentication failed (remainingRetries=${e.remainingRetries}) — NOT retrying (PIN-lockout safety)",
            )
            return@executeOnTseThread false
        } catch (e: WormException) {
            logger.error("✗ Time update failed: ${e.message} (code: ${e.error()})")
            return@executeOnTseThread false
        } finally {
            // Leaves the device with no user logged in, which is what keeps exports and
            // the entry browser working.
            runCatching { store.user_logout(WormUserId.WORM_USER_TIME_ADMIN) }
        }

        logger.info("✓ TSE 2.0 ready for transactions")
        true
    }

    /**
     * TSE 2.0 setup, delegated to the SDK's `tse_setup_ext`.
     *
     * The helper orders the steps for the attached generation: on v2 it initializes the
     * device *before* registering a client and sets the time, the reverse of the certified
     * v1 order (Swissbit guidance manual, Figure 4) that [performTseSetupWithState]
     * implements by hand. It covers credential derivation, all PUK and PIN changes
     * (including the Logger credentials that exist only on v2), initialization, the time,
     * and client registration in one call.
     *
     * Only the fresh path comes here. A partially configured device goes to
     * [recoverPartialSetupV2], because calling setup with credentials that have already
     * been changed would retry the initial ones and can brick the TSE.
     *
     * @return true if the TSE ends up INITIALIZED
     */
    private fun setupViaVendorHelper(
        store: com.secureflashcard.wormapi.WormStore,
        credState: CredentialChangeState,
    ): Boolean {
        if (credState.anyCredentialsChanged) {
            logger.info("TSE 2.0: credentials already changed — using partial-setup recovery")
            return recoverPartialSetupV2(store, credState)
        }

        val clientId = this.clientId!!
        logger.info("TSE 2.0 setup via tse_setup_ext (client '$clientId')...")
        val start = System.currentTimeMillis()

        try {
            store.tse_setup_ext(
                tseStatus.getCredentialSeed().toByteArray(Charsets.UTF_8),
                this.adminPuk!!.toByteArray(Charsets.UTF_8),
                this.adminPin!!.toByteArray(Charsets.UTF_8),
                tseStatus.getTimeAdminPin().toByteArray(Charsets.UTF_8),
                clientId,
            )
            logger.info("✓ tse_setup_ext completed in ${System.currentTimeMillis() - start}ms")
        } catch (e: WormAuthenticationFailedException) {
            tseStatus.recordAuthFailure(
                TseDto.TseAuthFailureInfo(
                    remainingRetries = e.remainingRetries,
                    pinBlocked = e.error() == WormError.WORM_ERROR_AUTHENTICATION_PIN_BLOCKED,
                )
            )
            logger.error("✗ tse_setup_ext authentication failed (remainingRetries=${e.remainingRetries}) — NOT retrying")
            return false
        } catch (e: WormException) {
            logger.error("✗ tse_setup_ext failed: ${e.message} (code: ${e.error()})")
            return false
        }

        val finalState = store.info().use { it.initializationState() }
        tseStatus.setInitializationState(finalState)
        logger.info("  ✓ Initialization state: $finalState")
        return finalState == WormInitializationState.WORM_INIT_INITIALIZED
    }

    /**
     * Completes a partially configured TSE 2.0 using the credentials it already has.
     *
     * `tse_setup_ext` must not be used here: it derives and applies the *initial*
     * credentials, which no longer work once they have been changed, and repeated PUK
     * failures can brick the device — which is why the existing credential-state gate is
     * kept rather than trusted away to the vendor helper.
     *
     * This is the one place setup branches on the generation, because the order differs:
     * on v2 the TSE must be initialized before the time can be set or a client registered,
     * the reverse of the certified v1 sequence.
     */
    private fun recoverPartialSetupV2(
        store: com.secureflashcard.wormapi.WormStore,
        credState: CredentialChangeState,
    ): Boolean {
        val clientId = this.clientId!!
        logger.warn("TSE 2.0: resuming a partially configured device")
        logger.warn(
            "  - PUK changed: ${credState.hasChangedPuk}, Admin PIN changed: " +
                "${credState.hasChangedAdminPin}, TimeAdmin PIN changed: " +
                "${credState.hasChangedTimeAdminPin}",
        )

        try {
            // Finish the credential changes setup did not get to, each one logging in as the
            // user whose PIN is changing — the SDK requires that, and on TSE 2.0 the single
            // active session means each login displaces the last. Leaving one behind would
            // keep a seed-derived secret the operator never chose, and for TimeAdmin it would
            // surface much later as unattended time sync failing with the configured PIN.
            completeOutstandingCredentialChangesV2(store, credState)

            // Only now, with the Admin PIN known to be the configured one, log in for the
            // rest. "Some credential changed" never meant "the Admin PIN changed": a device
            // interrupted after the PUK change still held the seed-derived PIN, and logging
            // in with the configured one spent an attempt against the three the device
            // grants before it blocks and needs its PUK.
            store.user_login(WormUserId.WORM_USER_ADMIN, this.adminPin!!.toByteArray(Charsets.UTF_8))

            if (store.info().use { it.initializationState() } == WormInitializationState.WORM_INIT_UNINITIALIZED) {
                store.tse_initialize()
                logger.info("✓ TSE initialized")
            }

            // Fixed order on v2: initialize, then time, then client registration.
            store.tse_updateTime(System.currentTimeMillis() / 1000)
            logger.info("✓ Time set")

            if (clientId !in store.tse_listRegisteredClients()) {
                store.tse_registerClient(clientId)
                logger.info("✓ Client '$clientId' registered")
            }
        } catch (e: WormAuthenticationFailedException) {
            tseStatus.recordAuthFailure(
                TseDto.TseAuthFailureInfo(
                    remainingRetries = e.remainingRetries,
                    pinBlocked = e.error() == WormError.WORM_ERROR_AUTHENTICATION_PIN_BLOCKED,
                )
            )
            logger.error(
                "✗ Recovery authentication failed (remainingRetries=${e.remainingRetries}) — NOT retrying (PIN-lockout safety)",
            )
            return false
        } catch (e: WormException) {
            logger.error("✗ Partial-setup recovery failed: ${e.message} (code: ${e.error()})")
            return false
        }

        val finalState = store.info().use { it.initializationState() }
        tseStatus.setInitializationState(finalState)
        return finalState == WormInitializationState.WORM_INIT_INITIALIZED
    }

    /**
     * Complete whichever credential changes an interrupted TSE 2.0 setup left outstanding.
     *
     * Requires Admin to be logged in already. Each change is skipped when the device reports
     * it as done, so this is safe to re-run — the flags come from the device, not from us.
     */
    private fun completeOutstandingCredentialChangesV2(
        store: com.secureflashcard.wormapi.WormStore,
        credState: CredentialChangeState,
    ) {
        if (credState.allCredentialsChanged) return

        store.user_deriveInitialCredentials(
            tseStatus.getCredentialSeed().toByteArray(Charsets.UTF_8),
        ).use { creds ->
            // The PUK change authenticates with the PUK itself and needs no session.
            if (!credState.hasChangedPuk) {
                store.user_change_puk(creds.adminPuk(), this.adminPuk!!.toByteArray(Charsets.UTF_8))
                logger.info("✓ PUK changed")
            }
            // user_change_pin requires the user whose PIN changes to be logged in, so each
            // change logs in first — the order the certified v1 sequence uses as well.
            if (!credState.hasChangedAdminPin) {
                store.user_login(WormUserId.WORM_USER_ADMIN, creds.adminPin())
                store.user_change_pin(
                    WormUserId.WORM_USER_ADMIN,
                    creds.adminPin(),
                    this.adminPin!!.toByteArray(Charsets.UTF_8),
                )
                logger.info("✓ Admin PIN changed")
            }
            if (!credState.hasChangedTimeAdminPin) {
                store.user_login(WormUserId.WORM_USER_TIME_ADMIN, creds.timeAdminPin())
                store.user_change_pin(
                    WormUserId.WORM_USER_TIME_ADMIN,
                    creds.timeAdminPin(),
                    tseStatus.getTimeAdminPin().toByteArray(Charsets.UTF_8),
                )
                logger.info("✓ TimeAdmin PIN changed")
            }
        }
    }

    /**
     * Get TSE status summary for diagnostics.
     *
     * @return Map with current TSE status information
     */
    fun getStatusSummary(): Map<String, Any> = tseStatus.executeOnTseThread {
        try {
            val store = tseStatus.getWormStore()
            store.info().use { info ->
                mapOf(
                    "connected" to tseStatus.isConnected(),
                    "initializationState" to info.initializationState().toString(),
                    "hasValidTime" to info.hasValidTime(),
                    "hasPassedSelfTest" to info.hasPassedSelfTest(),
                    "timeUntilNextSelfTest" to info.timeUntilNextSelfTest(),
                    "timeUntilNextTimeSynchronization" to info.timeUntilNextTimeSynchronization(),
                    "maxTimeSynchronizationDelay" to info.maxTimeSynchronizationDelay(),
                    "registeredClients" to info.registeredClients(),
                    "startedTransactions" to info.startedTransactions(),
                    "createdSignatures" to info.createdSignatures(),
                    "remainingSignatures" to info.remainingSignatures()
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to get status summary: ${e.message}", e)
            mapOf(
                "error" to (e.message ?: "Unknown error"),
                "connected" to tseStatus.isConnected()
            )
        }
    }
}
