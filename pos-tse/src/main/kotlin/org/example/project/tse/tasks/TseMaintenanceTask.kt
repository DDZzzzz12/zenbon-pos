package org.example.project.tse.tasks

import com.secureflashcard.wormapi.WormAuthenticationFailedException
import com.secureflashcard.wormapi.WormError
import com.secureflashcard.wormapi.WormUserId
import kotlinx.coroutines.*
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseSignOperation
import org.example.project.core.domain.error.TseCertificateExpiredException
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.signing.TseTransactionFinishOperation
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * TSE Maintenance Task - Handles scheduled self-test and time synchronization.
 *
 * Based on SwissBit TSE requirements:
 * https://support.gastro-mis.de/support/solutions/articles/36000240872
 *
 * Maintenance Operations:
 * 1. **Self-Test**: Must execute at least once every 25 hours
 *    - Checks `timeUntilNextSelfTest` from TSE
 *    - Executes self-test when required
 *    - Self-test may take up to 60 seconds
 *
 * 2. **Time Synchronization**: Must execute at intervals specified by TSE
 *    - Checks `timeUntilNextTimeSynchronization` from TSE
 *    - Executes time update when required
 *    - Critical after power cycles (TSE locked until synced)
 *
 * Standby/Wake Detection:
 * - Tracks wall-clock time between maintenance checks
 * - Detects time jumps indicating system standby/wake
 * - Triggers immediate self-test after wake from standby
 * - Note: TSE handles power cycle self-tests internally (USB power loss)
 * - This detection handles standby where USB power is maintained
 *
 * Scheduling Strategy:
 * - Checks TSE status every CHECK_INTERVAL (default: 5 minutes)
 * - Dynamically calculates next maintenance based on TSE requirements
 * - Prevents maintenance overlap with safety margin
 * - Handles errors gracefully and retries
 *
 * Thread Safety:
 * - Runs in coroutine scope with cancellation support
 * - All TSE operations use `tseStatus.executeOnTseThread {}`
 * - Only one maintenance operation executes at a time
 * - Uses singleton WormStore instance from TseStatus
 *
 * @property tseStatus TSE status and configuration manager with WormStore instance
 * @property scope Coroutine scope for task scheduling (injected by Spring)
 * @since 2.0.0
 */
class TseMaintenanceTask(
    private val tseStatus: TseStatus,
    private val scope: CoroutineScope,
) {


    private val logger = LoggerFactory.getLogger(TseMaintenanceTask::class.java)

    @Volatile
    private var maintenanceJob: Job? = null

    @Volatile
    private var isRunning = false

    /**
     * Tracks the last wall-clock time when a maintenance check was performed.
     * Used for detecting system standby/wake events via time jump detection.
     */
    @Volatile
    private var lastCheckTimestamp: Long = 0L

    /**
     * Tracks when started (unfinished) transactions were first seen.
     * Maps transactionNumber → firstSeenTimestamp (System.currentTimeMillis()).
     * Used to detect transactions that have been open for too long.
     */
    private val startedTransactionFirstSeen = ConcurrentHashMap<Long, Long>()

    companion object {
        private const val CHECK_INTERVAL_SECONDS = 600 // 10 minutes
        private const val SAFETY_MARGIN_SECONDS = 600 // 10 minutes before deadline
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 5000L // 5 seconds

        /**
         * Refresh the TSE time only when less than this much of its validity window
         * remains. Each update writes a log message and consumes TSE storage, so the
         * Swissbit guidance manual (2.6) warns against updating too often.
         */
        private const val TIME_SYNC_REFRESH_THRESHOLD_SECONDS = 1_800L

        /** Timeout in milliseconds after which an open transaction is considered orphaned (6y0 minutes). */
        private const val ORPHAN_TRANSACTION_TIMEOUT_MS = 60 * 60000L

        /** DSFinV-K abort processData for Belegabbruch with all-zero VAT and no payments. */
        private const val ABORT_PROCESS_DATA = "AVBelegabbruch^0.00_0.00_0.00_0.00_0.00^"

        /**
         * Threshold multiplier for detecting standby/wake.
         * If elapsed time exceeds (CHECK_INTERVAL * this multiplier), system likely slept.
         * Example: 1.5 means if 7.5+ minutes passed when we expected 5 min, wake detected.
         */
        private const val STANDBY_DETECTION_MULTIPLIER = 1.5

        /**
         * Minimum elapsed time (in seconds) to consider it a standby event.
         * Very short standby times (< 30 seconds) are ignored as they're likely
         * just processing delays. The TSE itself handles power cycle self-tests.
         */
        private const val MIN_STANDBY_THRESHOLD_SECONDS = 30
    }

    /**
     * Start the maintenance task.
     *
     * Begins periodic checking of TSE status and executes maintenance
     * operations (self-test and time sync) as required.
     *
     * The task runs in a coroutine and can be cancelled via stop().
     *
     * @return true if started successfully, false if already running
     */
    fun start(): Boolean {
        if (isRunning) {
            logger.warn("Maintenance task is already running")
            return false
        }

        if (tseStatus.getMode() == TseMode.SIMULATION || tseStatus.getMode() == TseMode.DISABLED) {
            logger.info("Maintenance not needed in SIMULATION mode")
            return false
        }

        if (!scope.isActive) {
            // A launch on a cancelled scope returns a dead job: the loop would never run
            // while isRunning reports true. Fail loudly instead.
            logger.error("Cannot start maintenance task: the provided coroutine scope is cancelled")
            return false
        }

        logger.info("╔════════════════════════════════════════════════════════════════════════════╗")
        logger.info("║                    TSE Maintenance Task Starting                          ║")
        logger.info("╚════════════════════════════════════════════════════════════════════════════╝")
        logger.info("  Check Interval: ${CHECK_INTERVAL_SECONDS}s (${CHECK_INTERVAL_SECONDS / 60} minutes)")
        logger.info("  Safety Margin: ${SAFETY_MARGIN_SECONDS}s (${SAFETY_MARGIN_SECONDS / 60} minutes)")
        logger.info("  Operations: Self-Test (every 25h) + Time Sync (TSE-defined interval)")

        isRunning = true
        maintenanceJob = scope.launch {
            try {
                // Deliberately registers no client, and does not list them.
                //
                // Registration needs Admin, which this unattended task must not hold, and a
                // TSE client is a Kasse — created deliberately, not as a side effect of a
                // maintenance loop starting. Listing is no better: tse_listRegisteredClients
                // requires Admin on TSE 1.x. Registration happens at commissioning and, in
                // future, through the terminal-pairing flow.
                runMaintenanceLoop()
            } catch (e: CancellationException) {
                logger.info("Maintenance task cancelled")
                throw e
            } catch (e: Exception) {
                logger.error("Maintenance task crashed: ${e.message}", e)
                isRunning = false
            }
        }

        logger.info("✓ Maintenance task started successfully")
        return true
    }

    /**
     * Stop the maintenance task.
     *
     * Signals cancellation of the maintenance job and returns immediately. This does
     * NOT block the calling thread and does NOT cancel the injected [scope]: the scope
     * is owned by the caller and may run unrelated work. (Cancelling it here also meant
     * that a later start() on the same scope launched an already-dead job — a silent
     * maintenance outage after any stop/start mode-change cycle.)
     *
     * Callers that must wait until the loop has fully terminated — e.g. before closing
     * the TSE connection on shutdown — use [stopAndJoin].
     */
    fun stop() {
        if (!isRunning) {
            logger.debug("Maintenance task is not running")
            return
        }

        logger.info("Stopping TSE maintenance task...")
        isRunning = false
        maintenanceJob?.cancel()
        logger.info("✓ Maintenance task stop signalled")
    }

    /**
     * Stop the maintenance task and suspend until the maintenance job has terminated.
     *
     * Unlike [stop], this waits for the job to finish reacting to cancellation (a job
     * currently blocked in a TSE operation completes that operation first). Safe to
     * call repeatedly and after [stop].
     */
    suspend fun stopAndJoin() {
        stop()
        maintenanceJob?.join()
        logger.info("✓ Maintenance task stopped")
    }

    /**
     * Main maintenance loop.
     *
     * Periodically checks TSE status and performs maintenance as needed.
     * Includes standby/wake detection via wall-clock time tracking.
     *
     * Performs an immediate initial check at startup to ensure TSE is healthy
     * before entering the periodic check loop. This prevents the health endpoint
     * from returning 503 during the initial delay period.
     */
    private suspend fun runMaintenanceLoop() {
        logger.info("Maintenance loop started")

        // Initialize wall-clock timestamp
        lastCheckTimestamp = System.currentTimeMillis()

        // Perform immediate initial maintenance check at startup
        // This ensures TSE health (self-test + time sync) before any delay
        logger.info("Performing initial maintenance check at startup...")
        try {
            performMaintenanceCheck()
            logger.info("✓ Initial maintenance check complete")
        } catch (e: Exception) {
            logger.error("Initial maintenance check failed: ${e.message}", e)
            throw e
        }

        while (isRunning) {
            try {
                // Wait for check interval
                delay(CHECK_INTERVAL_SECONDS * 1000L)

                // Check for standby/wake event
                val wakeDetected = detectStandbyWake()

                if (wakeDetected) {
                    // System woke from standby - perform immediate self-test
                    handleWakeFromStandby()
                }

                // Perform regular maintenance check
                performMaintenanceCheck()

                // Update wall-clock timestamp after successful check
                lastCheckTimestamp = System.currentTimeMillis()

            } catch (e: CancellationException) {
                logger.info("Maintenance loop cancelled")
                throw e
            } catch (e: Exception) {
                logger.error("Error in maintenance loop: ${e.message}", e)
                // Update timestamp even on error to avoid false wake detection
                lastCheckTimestamp = System.currentTimeMillis()
                // Continue loop despite error
            }
        }
    }

    /**
     * Detects if system was in standby based on wall-clock time jump.
     *
     * Compares actual elapsed time since last check against expected interval.
     * If elapsed time significantly exceeds expected (by STANDBY_DETECTION_MULTIPLIER),
     * the system was likely in standby/sleep.
     *
     * Note: For short standby times where USB power is maintained, the TSE
     * tracks its own `timeUntilNextSelfTest`. We check that in performMaintenanceCheck().
     * This detection is for cases where the system slept long enough that our
     * coroutine delay completed but significant real time passed.
     *
     * @return true if standby/wake was detected, false otherwise
     */
    private fun detectStandbyWake(): Boolean {
        val currentTime = System.currentTimeMillis()
        val expectedElapsed = CHECK_INTERVAL_SECONDS * 1000L
        val actualElapsed = currentTime - lastCheckTimestamp
        val threshold = (expectedElapsed * STANDBY_DETECTION_MULTIPLIER).toLong()

        // Calculate time jump (how much extra time passed beyond expected)
        val timeJumpSeconds = (actualElapsed - expectedElapsed) / 1000

        // Only consider it a standby event if:
        // 1. Elapsed time exceeds threshold (time jump detected)
        // 2. Time jump is significant enough (> MIN_STANDBY_THRESHOLD_SECONDS)
        if (actualElapsed > threshold && timeJumpSeconds >= MIN_STANDBY_THRESHOLD_SECONDS) {
            logger.warn("╔════════════════════════════════════════════════════════════════════════════╗")
            logger.warn("║                    STANDBY/WAKE DETECTED                                   ║")
            logger.warn("╚════════════════════════════════════════════════════════════════════════════╝")
            logger.warn("  Expected elapsed time: ${expectedElapsed / 1000}s")
            logger.warn("  Actual elapsed time: ${actualElapsed / 1000}s")
            logger.warn("  Time jump: ${timeJumpSeconds}s (system was likely sleeping)")
            return true
        }

        return false
    }

    /**
     * Handles wake from standby event.
     *
     * After system wakes from standby:
     * 1. Checks TSE status to determine if self-test is needed
     * 2. Always performs time synchronization (system clock may have drifted)
     * 3. Performs self-test if TSE indicates it's required
     *
     * According to Swissbit documentation:
     * - After power cycle, TSE runs self-test automatically
     * - If USB power was maintained during standby, TSE may still need self-test
     *   depending on how long it was inactive (checked via timeUntilNextSelfTest)
     */
    /**
     * Handles wake from standby event.
     *
     * IMPORTANT: Status check and maintenance operations are performed in separate
     * lock acquisitions to prevent deadlock. The Kotlin Mutex is non-reentrant.
     */
    private suspend fun handleWakeFromStandby() {
        logger.info("Handling wake from standby...")

        if (!tseStatus.hasWormStoreInstance()) {
            logger.warn("TSE not connected - skipping wake handling")
            return
        }

        // Determine what operations are needed (with lock)
        var needsSelfTest = false

        try {
            tseStatus.executeOnTseThread {
                val store = tseStatus.getWormStore()
                val info = store.info()

                info.use { wormInfo ->
                    val timeUntilSelfTest = wormInfo.timeUntilNextSelfTest()
                    val hasPassedSelfTest = wormInfo.hasPassedSelfTest()

                    logger.info("TSE status after wake:")
                    logger.info("  - Time until next self-test: ${timeUntilSelfTest}s")
                    logger.info("  - Has passed self-test: $hasPassedSelfTest")

                    // If TSE hasn't passed self-test (e.g., after power cycle), it runs internally
                    // We need to call tse_runSelfTest to satisfy the requirement
                    if (!hasPassedSelfTest) {
                        logger.warn("TSE has NOT passed self-test after wake - running self-test")
                        needsSelfTest = true
                    } else if (timeUntilSelfTest <= SAFETY_MARGIN_SECONDS) {
                        logger.warn("Self-test required after wake (${timeUntilSelfTest}s remaining)")
                        needsSelfTest = true
                    } else {
                        logger.info("TSE self-test not required (${timeUntilSelfTest}s until next required)")
                    }
                }
            }

            // Perform operations OUTSIDE the status check lock to prevent deadlock
            if (needsSelfTest) {
                performSelfTestWithRetry()
            }

            // Always sync time after wake - system clock may have drifted
            logger.info("Performing time synchronization after wake...")
            performTimeSyncWithRetry()

            logger.info("✓ Wake from standby handling complete")

        } catch (e: Exception) {
            logger.error("Error handling wake from standby: ${e.message}", e)
        }
    }

    /**
     * Perform maintenance check and execute required operations.
     *
     * Checks both self-test and time synchronization requirements.
     *
     * IMPORTANT: Status check and maintenance operations are performed in separate
     * lock acquisitions to prevent deadlock. The Kotlin Mutex is non-reentrant,
     * so nested executeOnTseThread calls would cause deadlock.
     */
    private suspend fun performMaintenanceCheck() {
        if (!tseStatus.hasWormStoreInstance()) {
            logger.debug("Maintenance check skipped: TSE not connected")
            return
        }

        logger.debug("─".repeat(80))
        logger.debug("Performing maintenance check...")

        // First, check status (with lock) and determine what needs to be done
        var needsSelfTest = false
        var needsTimeSync = false
        var startedTransactions: LongArray? = null
        try {
            tseStatus.executeOnTseThread {
                val store = tseStatus.getWormStore()
                val info = store.info()

                startedTransactions = store.transaction_listStartedTransactions()
                info.use { wormInfo ->
                    val certExpiration = wormInfo.certificateExpirationDate()
                    if (certExpiration >= lastCheckTimestamp) {
                        throw TseCertificateExpiredException(certExpiration)
                    }

                    // Check self-test requirement
                    val timeUntilSelfTest = wormInfo.timeUntilNextSelfTest()
                    logger.debug("Time until next self-test: ${timeUntilSelfTest}s (${timeUntilSelfTest / 3600}h)")

                    if (timeUntilSelfTest <= SAFETY_MARGIN_SECONDS) {
                        logger.info("⚠ Self-test required (${timeUntilSelfTest}s remaining)")
                        needsSelfTest = true
                    } else {
                        logger.debug("✓ Self-test not required yet")
                    }

                    // Check time synchronization requirement
                    val timeUntilSync = wormInfo.timeUntilNextTimeSynchronization()
                    val maxSyncDelay = wormInfo.maxTimeSynchronizationDelay()
                    logger.debug("Time until next time sync: ${timeUntilSync}s (${timeUntilSync / 3600}h)")
                    logger.debug("Max time sync delay: ${maxSyncDelay}s (${maxSyncDelay / 3600}h)")

                    if (timeUntilSync <= SAFETY_MARGIN_SECONDS) {
                        logger.info("⚠ Time synchronization required (${timeUntilSync}s remaining)")
                        needsTimeSync = true
                    } else {
                        logger.debug("✓ Time synchronization not required yet")
                    }
                }
            }

            // Now perform maintenance operations OUTSIDE the status check lock
            // This prevents deadlock since each operation acquires lock separately
            if (needsSelfTest) {
                performSelfTestWithRetry()
                // A self-test invalidates the TSE clock, so a time sync MUST follow or the
                // TSE reports an invalid time and rejects signing. Force it regardless of
                // whether the sync schedule was otherwise due.
                needsTimeSync = true
            }

            if (needsTimeSync) {
                performTimeSyncWithRetry()
            }

            logger.debug("Maintenance check complete")

            if (startedTransactions != null && startedTransactions.isNotEmpty()) {
                // Hardware-level safety net: clean up stale open transactions
                cleanupOrphanedTransactions(startedTransactions)
            }

        } catch (e: TseCertificateExpiredException) {
            throw e
        } catch (e: Exception) {
            logger.error("Maintenance check failed: ${e.message}", e)
        }
    }

    /**
     * Perform self-test with retry logic.
     *
     * Attempts self-test up to MAX_RETRY_ATTEMPTS times with delay between retries.
     *
     * @return true if successful, false if all retries failed
     */
    private suspend fun performSelfTestWithRetry(): Boolean =
        tseStatus.withMaintenanceSuspend("scheduled-selftest") {
            logger.info("╔════════════════════════════════════════════════════════════════════════════╗")
            logger.info("║                    Executing Scheduled Self-Test                          ║")
            logger.info("╚════════════════════════════════════════════════════════════════════════════╝")

            repeat(MAX_RETRY_ATTEMPTS) { attempt ->
                try {
                    if (attempt > 0) {
                        logger.info("Retry attempt ${attempt + 1}/$MAX_RETRY_ATTEMPTS")
                        delay(RETRY_DELAY_MS)
                    }

                    val startTime = System.currentTimeMillis()

                    tseStatus.executeOnTseThread {
                        val store = tseStatus.getWormStore()
                        val clientId = tseStatus.getClientId()

                        // TSE 1.x needs a *registered* client for the self test; TSE 2.0 ignores
                        // the parameter. Deliberately does not look one up — listRegisteredClients
                        // requires Admin on v1, which this unattended task must not hold.
                        if (clientId == null && !tseStatus.isTseV2()) {
                            logger.warn(
                                "Skipping self-test: no client ID configured. The TSE is not " +
                                    "commissioned for this register — no self-test can run until it is.",
                            )
                            return@executeOnTseThread
                        }

                        logger.info("Running self-test (may take up to 60 seconds)...")
                        tseStatus.runSelfTestOnDevice(clientId)

                        val duration = System.currentTimeMillis() - startTime
                        logger.info("✓ Self-test completed successfully in ${duration}ms")
                    }

                    return@withMaintenanceSuspend true

                } catch (e: Exception) {
                    logger.error("Self-test attempt ${attempt + 1} failed: ${e.message}")
                    if (attempt == MAX_RETRY_ATTEMPTS - 1) {
                        logger.error("✗ All self-test attempts failed")
                    }
                }
            }

            false
        }

    /**
     * Perform time synchronization with retry logic.
     *
     * Attempts time sync up to MAX_RETRY_ATTEMPTS times with delay between retries.
     *
     * @return true if successful, false if all retries failed
     */
    /**
     * Test seam: run one scheduled time sync without waiting for the maintenance interval.
     *
     * Exists so the PIN-lockout guard can be tested against the real retry loop rather than a
     * copy of it — the bug it guards was in the loop, not in the sync.
     */
    internal suspend fun performTimeSyncForTest(): Boolean = performTimeSyncWithRetry()

    private suspend fun performTimeSyncWithRetry(): Boolean =
        tseStatus.withMaintenanceSuspend("scheduled-timesync") {
            logger.info("╔════════════════════════════════════════════════════════════════════════════╗")
            logger.info("║                    Executing Time Synchronization                         ║")
            logger.info("╚════════════════════════════════════════════════════════════════════════════╝")

            // Set when the TimeAdmin PIN itself is rejected. Retrying that is not a retry —
            // it spends another of the three attempts the device grants before TimeAdmin
            // blocks, and this task runs every 10 minutes, so the loop alone would block it
            // on the very first cycle and leave unattended time sync dead until someone
            // unblocks with the PUK.
            var timeAdminPinRejected = false
            repeat(MAX_RETRY_ATTEMPTS) { attempt ->
                if (timeAdminPinRejected) return@repeat
                try {
                    if (attempt > 0) {
                        logger.info("Retry attempt ${attempt + 1}/$MAX_RETRY_ATTEMPTS")
                        delay(RETRY_DELAY_MS)
                    }

                    tseStatus.executeOnTseThread {
                        val store = tseStatus.getWormStore()
                        val timeAdminPin = tseStatus.getTimeAdminPin().toByteArray(Charsets.UTF_8)

                        // Skip a needless update: setting the time writes a log message and
                        // therefore consumes TSE storage (Swissbit guidance manual 2.6). A
                        // time still valid with comfortable margin does not need refreshing.
                        val needsUpdate = store.info().use { info ->
                            !info.hasValidTime() ||
                                info.timeUntilNextTimeSynchronization() <= TIME_SYNC_REFRESH_THRESHOLD_SECONDS
                        }
                        if (!needsUpdate) {
                            logger.info("✓ Time still valid with margin — skipping update")
                            return@executeOnTseThread
                        }

                        // Reuse an operator's Admin session if one is live. On TSE 2.0 only one
                        // user may be logged in, so logging in TimeAdmin here would evict an
                        // Admin the operator is mid-sitting with. Admin is itself allowed to set
                        // the time, so we just update under it and leave the login as we found
                        // it. The device is the source of truth: loggedInUser() reports the real
                        // session on 2.0, and always UNAUTHENTICATED on 1.x — which is fine,
                        // because 1.x allows multiple logins and has no eviction to avoid.
                        val alreadyAdmin = store.info().use { it.loggedInUser() } ==
                            WormUserId.WORM_USER_ADMIN
                        if (alreadyAdmin) {
                            val currentTime = System.currentTimeMillis() / 1000
                            store.tse_updateTime(currentTime)
                            logger.info(
                                "✓ Time synchronized under the active Admin session " +
                                    "(no TimeAdmin login, no eviction): $currentTime",
                            )
                            return@executeOnTseThread
                        }

                        // Login as TimeAdmin
                        try {
                            store.user_login(WormUserId.WORM_USER_TIME_ADMIN, timeAdminPin)
                        } catch (e: WormAuthenticationFailedException) {
                            // A rejected PIN, not a transient fault. Recorded and surfaced so
                            // the operator can fix the configured PIN, and emphatically not
                            // retried — see timeAdminPinRejected above.
                            tseStatus.recordAuthFailure(
                                TseDto.TseAuthFailureInfo(
                                    remainingRetries = e.remainingRetries,
                                    pinBlocked = e.error() == WormError.WORM_ERROR_AUTHENTICATION_PIN_BLOCKED,
                                )
                            )
                            timeAdminPinRejected = true
                            logger.error(
                                "✗ TimeAdmin login rejected (remainingRetries=${e.remainingRetries}, " +
                                    "blocked=${e.error() == WormError.WORM_ERROR_AUTHENTICATION_PIN_BLOCKED}) " +
                                    "— NOT retrying (PIN-lockout safety). Check the configured TimeAdmin PIN.",
                            )
                            return@executeOnTseThread
                        } catch (e: Exception) {
                            // Anything else — notably "already logged in" — is harmless here.
                            logger.debug("TimeAdmin login: ${e.message}")
                        }

                        try {
                            // Update time
                            val currentTime = System.currentTimeMillis() / 1000
                            store.tse_updateTime(currentTime)

                            logger.info("✓ Time synchronized to: $currentTime (${java.util.Date(currentTime * 1000)})")
                        } finally {
                            // MANDATORY on TSE 2.0: TimeAdmin may neither export nor read
                            // entries, so leaving it as the resting user blocks every later
                            // backup and the entry browser with NOT_AUTHORIZED. In a finally
                            // block so a failed update cannot strand the device either.
                            // Harmless on v1, where logout is simply targeted.
                            try {
                                store.user_logout(WormUserId.WORM_USER_TIME_ADMIN)
                                logger.debug("TimeAdmin logged out after time sync")
                            } catch (e: Exception) {
                                logger.debug("TimeAdmin logout: ${e.message}")
                            }
                        }
                    }

                    if (timeAdminPinRejected) return@withMaintenanceSuspend false
                    return@withMaintenanceSuspend true

                } catch (e: Exception) {
                    logger.error("Time sync attempt ${attempt + 1} failed: ${e.message}")
                    if (attempt == MAX_RETRY_ATTEMPTS - 1) {
                        logger.error("✗ All time sync attempts failed")
                    }
                }
            }

            false
        }

    /**
     * Hardware-level safety net for cleaning up orphaned TSE transactions.
     *
     * Queries the TSE hardware for all started (unfinished) transactions, tracks when
     * each was first seen, and aborts any that have been open for longer than
     * [ORPHAN_TRANSACTION_TIMEOUT_MS]. This catches edge cases where the main server's
     * DB record may not exist (e.g., if the DB write failed after TSE transaction_start).
     *
     * Algorithm:
     * 1. List all started transactions from TSE hardware (acquires lock)
     * 2. Track new transactions with current timestamp
     * 3. Remove transactions that are no longer in the started list (already finished)
     * 4. For transactions first seen > 20 min ago: finish with Belegabbruch abort
     *
     * Note: The main server handles its own DB cleanup separately. This is a hardware-only
     * safety net to prevent TSE resource exhaustion from stale open transactions.
     */
    private suspend fun cleanupOrphanedTransactions(startedTransactions: LongArray) {
        if (!tseStatus.hasWormStoreInstance()) {
            return
        }

        try {

            if (startedTransactions.isEmpty()) {
                // No open transactions — clear tracking map
                if (startedTransactionFirstSeen.isNotEmpty()) {
                    startedTransactionFirstSeen.clear()
                }
                return
            }

            val currentTime = System.currentTimeMillis()
            val startedSet = startedTransactions.toSet()

            // Step 2: Remove transactions no longer in started list (already completed)
            startedTransactionFirstSeen.keys.removeAll { it !in startedSet }

            // Step 3: Track newly seen transactions
            for (txNum in startedTransactions) {
                startedTransactionFirstSeen.putIfAbsent(txNum, currentTime)
            }

            // Step 4: Find and abort orphaned transactions (open > timeout)
            val orphaned = startedTransactionFirstSeen.filter { (_, firstSeen) ->
                (currentTime - firstSeen) > ORPHAN_TRANSACTION_TIMEOUT_MS
            }

            if (orphaned.isEmpty()) {
                logger.debug(
                    "Orphan check: ${startedTransactions.size} open transaction(s), none timed out"
                )
                return
            }

            logger.warn("Found ${orphaned.size} orphaned TSE transaction(s) to abort")

            for ((txNum, firstSeen) in orphaned) {
                try {
                    val ageMinutes = (currentTime - firstSeen) / 60_000

                    // Use the maintenance client ID for cleanup operations
                    val clientId = tseStatus.getClientId() ?: continue

                    logger.info("Aborting orphaned TSE transaction #$txNum (open for ${ageMinutes}min)")

                    // Use TseTransactionFinishOperation which handles lock acquisition internally
                    val finishOp = TseTransactionFinishOperation(
                        command = TseSignOperation.SIGN_BILL.op,
                        transactionNumber = txNum,
                        value = ABORT_PROCESS_DATA,
                        clientId = clientId
                    )

                    try {
                        finishOp.execute(tseStatus)
                        logger.info("Orphaned TSE transaction #$txNum aborted successfully")
                        startedTransactionFirstSeen.remove(txNum)
                    } catch (e: PosException) {
                        logger.warn(
                            "Failed to abort orphaned TSE transaction #$txNum: code=${e.code} msg=${e.message}",
                            e
                        )
                    }
                } catch (e: Exception) {
                    logger.error("Error aborting orphaned TSE transaction #$txNum: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            logger.error("Error during orphaned transaction cleanup: ${e.message}", e)
        }
    }

    /**
     * Manually trigger maintenance operations.
     *
     * Performs both self-test and time synchronization immediately.
     * Useful for testing or forcing maintenance outside the schedule.
     *
     * @return Pair of (selfTestSuccess, timeSyncSuccess)
     */
    suspend fun performManualMaintenance(): Pair<Boolean, Boolean> =
        tseStatus.withMaintenanceSuspend("manual-maintenance") {
            logger.info("Manual maintenance triggered")

            val selfTestSuccess = performSelfTestWithRetry()
            val timeSyncSuccess = performTimeSyncWithRetry()

            // Reset timestamp to prevent false wake detection after manual maintenance
            lastCheckTimestamp = System.currentTimeMillis()

            logger.info("Manual maintenance complete: Self-test=$selfTestSuccess, TimeSync=$timeSyncSuccess")

            Pair(selfTestSuccess, timeSyncSuccess)
        }

    /**
     * Notify the maintenance task that the system woke from standby.
     *
     * Can be called externally when the system receives a wake signal
     * (e.g., via D-Bus systemd-logind on Linux, or system events on Windows).
     * This bypasses the time-jump detection and immediately triggers wake handling.
     *
     * Usage example (Linux with D-Bus):
     * ```kotlin
     * // Listen for PrepareForSleep(false) signal from org.freedesktop.login1
     * maintenanceTask.notifyWakeFromStandby()
     * ```
     *
     * @return true if wake handling was triggered, false if TSE not available
     */
    suspend fun notifyWakeFromStandby(): Boolean {
        logger.info("External wake notification received")

        if (!tseStatus.hasWormStoreInstance()) {
            logger.warn("TSE not connected - cannot handle wake notification")
            return false
        }

        handleWakeFromStandby()

        // Reset timestamp after handling wake
        lastCheckTimestamp = System.currentTimeMillis()

        return true
    }

    /**
     * Get current standby detection status for diagnostics.
     *
     * @return Map containing detection metrics
     */
    fun getStandbyDetectionStatus(): Map<String, Any> {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastCheck = if (lastCheckTimestamp > 0) {
            (currentTime - lastCheckTimestamp) / 1000
        } else {
            -1L
        }

        return mapOf(
            "lastCheckTimestamp" to lastCheckTimestamp,
            "currentTimestamp" to currentTime,
            "secondsSinceLastCheck" to timeSinceLastCheck,
            "checkIntervalSeconds" to CHECK_INTERVAL_SECONDS,
            "standbyDetectionThreshold" to (CHECK_INTERVAL_SECONDS * STANDBY_DETECTION_MULTIPLIER).toLong(),
            "minStandbyThresholdSeconds" to MIN_STANDBY_THRESHOLD_SECONDS,
            "isRunning" to isRunning
        )
    }
}
