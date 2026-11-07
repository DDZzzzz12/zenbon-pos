/**
 * Background service that rescues fiscal Vorgänge stranded as `tseSigningStatus = PENDING`
 * with NO matching row in the TSE signing queue.
 *
 * Round-3 TSER-9: a crash between entity commit and enqueue strands the Vorgang as PENDING
 * with no queue row — the export claims "Nachsignierung ausstehend", so something must actually
 * deliver it.
 *
 * Signing is two separate transactions at every call site: Phase 1 commits the entity with the
 * default `PENDING` status; Phase 2 signs on the TSE and enqueues for Nachsignieren. A crash
 * between the two — OR an enqueue failure — leaves the entity PENDING with no queue row. The
 * Nachsignieren worker only ever processes queue rows, so nothing would sign it: the entity stays
 * unsigned forever while the DSFinV-K export documents a Nachsignierung that will never happen.
 *
 * This sweeper reconciles PENDING entities against the queue and enqueues the orphans, rebuilding
 * the SAME processData the original signing path would have produced (via the shared
 * [ProcessDataBuilder.buildForBill]) so the re-enqueue signs byte-identical data.
 *
 * Scope (round-3): BILL forward sales only — the highest-volume fiscal entity, and the only one
 * whose processData is a pure function of the persisted entity plus a stored terminal/clientId.
 * ORDER, KASSENBUCH and non-forward bills (storno reversals) are a
 * stated follow-up; see the TODO in [sweep].
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.tse.data.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.tse.domain.repository.TseSigningQueueRepository
import org.example.project.features.tse.domain.service.TseSigningQueueService
import org.example.project.features.tse.domain.service.TseTransactionService
import org.example.project.utils.TestOnlyApi
import org.example.project.utils.builder.ProcessDataBuilder
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentInstant
import org.example.project.utils.formatter.getTimeZone
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

/**
 * Scheduled background service that re-enqueues PENDING Vorgänge orphaned without a queue row.
 *
 * Lifecycle: managed by Koin DI — starts on initialization, shuts down with the app.
 *
 * @property queueRepository Used to check whether a queue row already owns an entity.
 * @property queueService Used to enqueue the stranded entity for Nachsignieren.
 */
class TsePendingSweeper(
    private val queueRepository: TseSigningQueueRepository,
    private val queueService: TseSigningQueueService,
) {
    private val logger = LoggerFactory.getLogger(TsePendingSweeper::class.java)

    /** Background executor for periodic sweeps. */
    private val sweepExecutor =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "TsePendingSweeper").apply {
                isDaemon = true
            }
        }

    /** Coroutine scope for async operations within the scheduled task. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Handle for the scheduled task (for cancellation). */
    private var scheduledTask: ScheduledFuture<*>? = null

    /** Flag indicating if the service is paused. */
    private val isPaused = AtomicBoolean(false)

    /** Flag indicating if the service is running. */
    private val isServiceRunning = AtomicBoolean(false)

    companion object {
        /** Sweep interval in seconds (2 minutes). */
        const val SWEEP_INTERVAL_SECONDS = 120L

        /** Initial delay before the first sweep in seconds (90 seconds). */
        const val INITIAL_DELAY_SECONDS = 90L

        /**
         * Grace period in minutes. Only entities that have been PENDING WITHOUT a queue row for
         * longer than this are swept, so the sweeper never races the normal signing path (whose
         * Phase 2 enqueue follows the Phase 1 commit within milliseconds).
         */
        const val GRACE_PERIOD_MINUTES = 10L

        /** Singleton instance set by DI container. */
        @Volatile
        private var instance: TsePendingSweeper? = null

        /** Gets the singleton instance. */
        fun getInstance(): TsePendingSweeper? = instance

        private fun register(service: TsePendingSweeper) {
            instance = service
        }
    }

    init {
        register(this)
        logger.debug(
            "TsePendingSweeper initialized " +
                "(interval: ${SWEEP_INTERVAL_SECONDS}s, gracePeriod: ${GRACE_PERIOD_MINUTES}min)",
        )
    }

    /** Starts the periodic sweep task. Should be called after DI initialization is complete. */
    fun start() {
        if (isServiceRunning.get()) {
            logger.warn("TsePendingSweeper already running, ignoring start request")
            return
        }

        scheduledTask =
            sweepExecutor.scheduleAtFixedRate(
                { executeSweepTask() },
                INITIAL_DELAY_SECONDS,
                SWEEP_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )

        isServiceRunning.set(true)
        logger.info("TsePendingSweeper started")
    }

    /** Pauses the sweeper. The scheduled task continues to run but skips processing. */
    fun pause() {
        isPaused.set(true)
        logger.info("TsePendingSweeper paused")
    }

    /** Resumes the sweeper after a pause. */
    fun resume() {
        isPaused.set(false)
        logger.info("TsePendingSweeper resumed")
    }

    /**
     * Executes a single sweep cycle. Called periodically by the scheduled executor.
     * Handles all errors internally to prevent task termination.
     */
    @OptIn(TestOnlyApi::class) // sweep: same-file production use of the test-visible hook
    private fun executeSweepTask() {
        if (isPaused.get()) {
            logger.debug("TsePendingSweeper is paused, skipping cycle")
            return
        }

        try {
            serviceScope.launch {
                try {
                    sweep()
                } catch (e: Exception) {
                    logger.error("Error in TsePendingSweeper cycle", e)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to launch TsePendingSweeper task", e)
        }
    }

    /**
     * Finds PENDING bills that never made it into the signing queue and enqueues them for
     * Nachsignieren, rebuilding the same processData the live signing path would have produced.
     *
     * A bill qualifies when it is PENDING, unsigned (`tseTransactionId == null`), a forward sale
     * (`deletedBillId == null`), older than the grace period,
     * and has NO existing queue row. The processData is rebuilt via the SHARED
     * [ProcessDataBuilder.buildForBill] so the enqueue is byte-identical to the original attempt.
     *
     * @return the number of bills enqueued this cycle.
     */
    @OptIn(ExperimentalTime::class)
    @TestOnlyApi
    suspend fun sweep(): Int =
        dbQuery {
            val cutoff = currentInstant().minus(GRACE_PERIOD_MINUTES.minutes).toLocalDateTime(getTimeZone())

            val stranded =
                Bill
                    .find {
                        (BillTable.tseSigningStatus eq TseSigningStatus.PENDING) and
                            BillTable.tseTransactionId.isNull() and
                            BillTable.deletedBillId.isNull() and
                            (BillTable.endTime less cutoff)
                    }.toList()

            var enqueued = 0
            for (bill in stranded) {
                val referenceId = bill.id.value.toString()

                // A queue row (ANY status) means the worker path already owns this Vorgang.
                if (queueRepository.existsForReference(referenceId, TseEntityType.BILL)) continue

                val clientId = bill.terminal?.clientId
                if (clientId == null) {
                    logger.warn(
                        "TsePendingSweeper: bill ${bill.billNumber} (id=$referenceId) is PENDING with no " +
                            "queue row but has no terminal/clientId — cannot rebuild the signing client; skipping",
                    )
                    continue
                }

                // NOTE: do NOT also skip on the terminal's CURRENT fiscal/tseClientRegistered state.
                // syncTseRegistrationStatus legitimately flips tseClientRegistered to false on a TSE
                // swap — a genuinely-stranded Ausfall bill from BEFORE the swap must still be
                // Nachsigniert, not silently and permanently dropped. clientId == null above is the
                // only case where re-signing is truly impossible (no client to sign under at all).

                val processData = ProcessDataBuilder.buildForBill(bill)
                try {
                    queueService.enqueue(
                        referenceId = referenceId,
                        entityType = TseEntityType.BILL,
                        processType = TseTransactionService.PROCESS_TYPE_RECEIPT,
                        processData = processData,
                        clientId = clientId,
                        reason = TseLateSigningReason.PENDING_SWEEP,
                        reasonText = "Unsigniert vorgefunden (PENDING, keine Warteschlange) – vom Sweeper eingereiht",
                    )
                    enqueued++
                    logger.info(
                        "TsePendingSweeper: enqueued stranded PENDING bill ${bill.billNumber} " +
                            "(id=$referenceId) for Nachsignierung",
                    )
                } catch (e: Exception) {
                    logger.error(
                        "TsePendingSweeper: failed to enqueue stranded bill ${bill.billNumber} " +
                            "(id=$referenceId): ${e.message}",
                        e,
                    )
                }
            }

            if (enqueued > 0) {
                logger.info("TsePendingSweeper: enqueued $enqueued stranded PENDING bill(s) this cycle")
            } else {
                logger.debug("TsePendingSweeper: no stranded PENDING bills found")
            }

            // TODO round-3 TSER-9 follow-up: ORDER (Bestellung-V1) and KASSENBUCH (SonstigerVorgang /
            // Kassenbeleg-V1), plus storno reverse bills, are NOT
            // yet swept. Their processData is not a pure function of the persisted entity (order-item
            // DTO mapping; the KassenbuchHeader carries no clientId and its build branches on entryType;
            // non-forward bills sign reversed buckets and refund payments), so a naive
            // rebuild here would risk signing DIVERGENT data. Extend once each build is cleanly
            // extractable into a shared entity->processData helper like buildForBill.
            enqueued
        }

    /** Shuts down the sweep executor gracefully. Should be called during application shutdown. */
    fun shutdown() {
        try {
            isServiceRunning.set(false)
            scheduledTask?.cancel(false)
            sweepExecutor.shutdown()
            if (!sweepExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                sweepExecutor.shutdownNow()
            }
            logger.info("TsePendingSweeper shut down")
        } catch (e: InterruptedException) {
            sweepExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
