package org.example.project.features.tse.data.provider.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.tse.data.service.TseAdminUnlock.Companion.isPinRejection
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.operations.admin.time.UpdateTimeOperation
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Brings a re-plugged TSE back to a signing state: self test, then time update.
 *
 * A Swissbit TSE that loses power comes back with `passedSelfTest=false` and `validTime=false`
 * (F50, real hardware). Startup runs both steps (`TseInitializationManager`), but re-acquiring the
 * mount point after an unplug ([LocalTseLifecycleManager.reacquireIfLost]) only re-attaches the
 * device and restores its client and TimeAdmin PIN. The signing sweeper waits for a *healthy* check before it signs anything, so the
 * sign-time `NEEDS_SELF_TEST` remediation is never reached either, and the scheduled maintenance
 * task only runs every ten minutes and fails on the same precondition. Bills stayed PENDING until
 * an operator ran `POST /api/tse/selftest` and `/updatetime` by hand.
 *
 * [onHealthResult] is called from every health check. It starts a recovery only when the shop is
 * NORMAL, the device answered, is commissioned (`initialized`), is missing a self test or a valid
 * time, and no other maintenance is in flight. The work runs on [scope], never on the caller's
 * thread — a self test takes ~14 s and up to a minute.
 *
 * Guards:
 * - one recovery at a time ([inFlight]);
 * - at most one attempt per [backoff];
 * - a rejected TimeAdmin PIN stops all further attempts, with one WARN: retrying a wrong PIN
 *   unattended walks the device towards its PIN lockout. A later healthy result re-arms it (the
 *   operator fixed the PIN and brought the TSE up some other way).
 *
 * Needs no Admin PIN: the self test logs in nobody, the time update logs in as TimeAdmin with the
 * stored TimeAdmin PIN — the one credential the ERS may keep (AGD 2.4/2.5).
 *
 * The self test runs for the registered client ([LocalTseSelfTest]). It used to pass none and rely
 * on the driver's configured one, which a TSE plugged in while the server ran never had (real
 * Swissbit TSE 1.x, 2026-10-10): every attempt failed before any device call, every cycle, until a
 * restart. With no registered terminal on a TSE 1.x the self test is still refused before any
 * device call — an ordinary failure here, retried after the [backoff], so registering a terminal
 * lets the next attempt through.
 *
 * @param selfTest runs the device self test (production: [LocalTseSelfTest] on the TSE thread).
 * @param updateTime sets the device clock (production: [UpdateTimeOperation] on the TSE thread).
 * @param scope where a recovery runs, off the health-check caller.
 * @param backoff minimum spacing between two attempts.
 * @param nowMillis monotonic clock in milliseconds; injectable for tests.
 * @param afterRecovery runs once both steps succeeded (production: start the scheduled maintenance
 *   if nothing has — [LocalTseLifecycleManager.ensureMaintenanceTaskRunning]).
 */
class TsePowerLossRecovery(
    private val selfTest: suspend () -> Unit,
    private val updateTime: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val backoff: Duration = DEFAULT_BACKOFF,
    private val nowMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val afterRecovery: () -> Unit = {},
) {
    private val logger = LoggerFactory.getLogger(TsePowerLossRecovery::class.java)

    private val inFlight = AtomicBoolean(false)

    @Volatile
    private var lastAttemptMillis: Long? = null

    @Volatile
    private var stoppedByAuthFailure = false

    /**
     * Starts a recovery when [result] shows a commissioned, answering TSE that lost its self test
     * or its time. Returns immediately.
     *
     * @param configuredMode the shop's configured mode; only NORMAL drives hardware.
     * @param result the raw device health, or null when the device did not answer.
     * @param inMaintenance whether another maintenance operation is in flight.
     * @return true when a recovery was launched by this call.
     */
    fun onHealthResult(
        configuredMode: TseMode,
        result: TseDto.HealthCheckResultDto?,
        inMaintenance: Boolean,
    ): Boolean {
        if (result?.healthy == true) {
            stoppedByAuthFailure = false
            return false
        }
        if (!needsRecovery(configuredMode, result, inMaintenance)) return false
        if (stoppedByAuthFailure) return false
        if (withinBackoff()) return false
        if (!inFlight.compareAndSet(false, true)) return false

        lastAttemptMillis = nowMillis()
        val runSelfTest = result?.passedSelfTest == false
        scope.launch {
            try {
                recover(runSelfTest)
            } finally {
                inFlight.set(false)
            }
        }
        return true
    }

    private fun needsRecovery(
        configuredMode: TseMode,
        result: TseDto.HealthCheckResultDto?,
        inMaintenance: Boolean,
    ): Boolean {
        if (configuredMode != TseMode.NORMAL) return false
        val answered = result ?: return false
        if (!answered.initialized) return false
        if (inMaintenance) return false
        return !answered.passedSelfTest || !answered.validTime
    }

    private fun withinBackoff(): Boolean {
        val last = lastAttemptMillis ?: return false
        return nowMillis() - last < backoff.inWholeMilliseconds
    }

    private suspend fun recover(runSelfTest: Boolean) {
        try {
            if (runSelfTest) selfTest()
            updateTime()
            val steps = if (runSelfTest) "self-test + time update" else "time update"
            logger.info("TSE recovered after power loss: $steps")
            afterRecovery()
        } catch (e: CancellationException) {
            throw e
        } catch (e: PosException) {
            handleFailure(e)
        } catch (e: Exception) {
            logger.warn("TSE power-loss recovery failed (${e.message}); retrying in at most $backoff")
        }
    }

    private fun handleFailure(e: PosException) {
        if (!e.isPinRejection()) {
            logger.warn("TSE power-loss recovery failed (${e.code}: ${e.message}); retrying in at most $backoff")
            return
        }
        stoppedByAuthFailure = true
        logger.warn(
            "TSE power-loss recovery stopped: the TSE rejected the stored TimeAdmin PIN (${e.message}). " +
                "Not retrying, to protect the PIN from lockout. Check the TimeAdmin PIN in the TSE " +
                "configuration, then run the self test and time update from the TSE settings.",
        )
    }

    companion object {
        val DEFAULT_BACKOFF: Duration = 60.seconds

        private const val NANOS_PER_MILLI = 1_000_000L

        /** Unattended: none of these steps may need the Admin PIN (see `LocalTseAdminProvider`). */
        private const val NO_ADMIN_PIN = ""

        /**
         * The recovery for the in-process TSE: the same self test as the manual "Selbsttest"
         * ([LocalTseSelfTest]) and the same `pos-tse` time update as `LocalTseAdminProvider.updateTime`,
         * each under its own maintenance mark and serialized on the TSE thread by the operation itself.
         */
        fun forLocalTse(lifecycleManager: LocalTseLifecycleManager): TsePowerLossRecovery {
            val selfTest = LocalTseSelfTest.forLocalTse(lifecycleManager, SELF_TEST_OP)
            return TsePowerLossRecovery(
                selfTest = { selfTest.run(force = false) },
                updateTime = {
                    val status = lifecycleManager.getTseStatus()
                    status.withMaintenanceSuspend(TIME_SYNC_OP) {
                        UpdateTimeOperation(adminPin = NO_ADMIN_PIN).execute(status)
                    }
                },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                afterRecovery = lifecycleManager::ensureMaintenanceTaskRunning,
            )
        }

        private const val SELF_TEST_OP = "recovery-selftest"
        private const val TIME_SYNC_OP = "recovery-timesync"
    }
}
