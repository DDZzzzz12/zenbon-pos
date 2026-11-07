package org.example.project.features.scheduler.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.scheduler.JobStatus
import org.example.project.core.domain.enums.scheduler.TriggerSource
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.error.PosErrorCode
import org.example.project.features.scheduler.domain.models.JobExecution
import org.example.project.features.scheduler.domain.models.ScheduledJob
import org.example.project.features.scheduler.domain.repository.JobExecutionRepository
import org.example.project.features.scheduler.domain.repository.ScheduledJobRepository
import org.example.project.features.scheduler.domain.service.JobPartiallyFailedException
import org.example.project.features.scheduler.domain.service.ScheduledJobExecutor
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.TestOnlyApi
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SchedulerEngine(
    private val jobRepo: ScheduledJobRepository,
    private val execRepo: JobExecutionRepository,
    private val executors: List<ScheduledJobExecutor>,
    private val calculator: NextRunCalculator = NextRunCalculator(),
    private val clock: () -> Instant = { Instant.now() },
    /**
     * The restored folder paths currently awaiting review (see `RestoredPathSetting`). A job whose
     * executor writes to one of them is skipped on schedule and refused on "run now".
     */
    private val pendingRestoredPaths: suspend () -> Set<RestoredPathSetting> = { emptySet() },
) {
    private val logger = LoggerFactory.getLogger(SchedulerEngine::class.java)

    private val tickExecutor =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "Scheduler").apply { isDaemon = true }
        }
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)
    private var scheduledFuture: ScheduledFuture<*>? = null

    companion object {
        const val TICK_INTERVAL_SECONDS = 30L
        const val INITIAL_DELAY_SECONDS = 20L
    }

    @OptIn(TestOnlyApi::class)
    fun start() {
        if (isRunning.getAndSet(true)) {
            logger.warn("SchedulerEngine already running")
            return
        }
        runBlocking { recoverOrphans() }
        scheduledFuture =
            tickExecutor.scheduleAtFixedRate(
                { safeTick() },
                INITIAL_DELAY_SECONDS,
                TICK_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )
        logger.info("SchedulerEngine started (tick=${TICK_INTERVAL_SECONDS}s)")
    }

    fun shutdown() {
        try {
            isRunning.set(false)
            scheduledFuture?.cancel(false)
            tickExecutor.shutdown()
            if (!tickExecutor.awaitTermination(5, TimeUnit.SECONDS)) tickExecutor.shutdownNow()
            logger.info("SchedulerEngine shut down")
        } catch (e: InterruptedException) {
            tickExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    /** Manual trigger path. Records last run/status but does NOT advance next_run_at. Returns the final execution row. */
    suspend fun runNow(job: ScheduledJob): JobExecution {
        val blocked = blockedRestoredPaths(job)
        if (blocked.isNotEmpty()) {
            throw PosException(
                PosErrorCode.RESTORED_PATHS_NEED_REVIEW,
                HttpStatusCode.Conflict,
                "Job ${job.name} (${job.type}) writes to restored paths that await review: $blocked. " +
                    "Review them via /api/v1/backup/restored-paths first.",
            )
        }
        val started = clock()
        val running = dbQuery { execRepo.insertRunning(job.id, TriggerSource.MANUAL, started) }
        return runAndRecord(job, running, advanceSchedule = false)
    }

    // ---------- internals ----------

    @OptIn(TestOnlyApi::class)
    private fun safeTick() {
        try {
            runBlocking { processDueJobs() }
        } catch (e: Throwable) {
            logger.error("tick loop failure", e)
        }
    }

    /** Exposed for testing. Queries due jobs and dispatches each one. */
    @TestOnlyApi
    suspend fun processDueJobs() {
        val dueJobs = dbQuery { jobRepo.findDue(clock()) }
        dueJobs.forEach { job ->
            coroutineScope.launch { handleScheduledJob(job) }
        }
    }

    private suspend fun handleScheduledJob(job: ScheduledJob) {
        val now = clock()
        val blocked = blockedRestoredPaths(job)
        if (blocked.isNotEmpty()) {
            skipForRestoredPaths(job, blocked, now)
            return
        }
        val running =
            dbQuery {
                if (execRepo.hasRunningFor(job.id)) {
                    execRepo.insertSkipped(job.id, TriggerSource.SCHEDULED, now, "previous run still in progress")
                    null
                } else {
                    execRepo.insertRunning(job.id, TriggerSource.SCHEDULED, now)
                }
            } ?: run {
                advanceNextRun(job, JobStatus.SKIPPED)
                return
            }
        runAndRecord(job, running, advanceSchedule = true)
    }

    /**
     * The restored paths awaiting review that [job]'s executor writes to. Empty — the job may run —
     * when there is none, or when the job has no executor (that failure is recorded as before).
     */
    private suspend fun blockedRestoredPaths(job: ScheduledJob): Set<RestoredPathSetting> {
        val written = executors.firstOrNull { it.type == job.type }?.restoredPathsWritten.orEmpty()
        if (written.isEmpty()) return emptySet()
        return written intersect pendingRestoredPaths()
    }

    /**
     * Records the paused run as SKIPPED and moves the schedule on. Nothing is lost by skipping: a
     * DATEV catch-up enumerates every period the export log lacks, so the first run after the
     * review exports the skipped periods too.
     */
    private suspend fun skipForRestoredPaths(
        job: ScheduledJob,
        blocked: Set<RestoredPathSetting>,
        now: Instant,
    ) {
        logger.warn(
            "job {} ({}) skipped: it writes to restored paths {} that belong to another installation " +
                "and await an administrator's review (/api/v1/backup/restored-paths)",
            job.id,
            job.type,
            blocked,
        )
        dbQuery {
            execRepo.insertSkipped(job.id, TriggerSource.SCHEDULED, now, "restored paths need review: $blocked")
        }
        advanceNextRun(job, JobStatus.SKIPPED)
    }

    private suspend fun runAndRecord(
        job: ScheduledJob,
        running: JobExecution,
        advanceSchedule: Boolean,
    ): JobExecution {
        val executor = executors.firstOrNull { it.type == job.type }
        if (executor == null) {
            return finishExecution(job, running, JobStatus.FAILED, null, "no executor registered for type ${job.type}", advanceSchedule)
        }

        val output: String =
            try {
                executor.execute(job.parametersJson)
            } catch (e: JobPartiallyFailedException) {
                logger.warn("job {} ({}) completed with failures: {}", job.id, job.type, e.message)
                return finishExecution(job, running, JobStatus.FAILED, e.resultMessage, e.message, advanceSchedule)
            } catch (e: PosException) {
                return finishExecution(job, running, JobStatus.FAILED, null, e.message ?: e.code.name, advanceSchedule)
            } catch (e: Throwable) {
                logger.error("executor threw for job ${job.id}", e)
                return finishExecution(job, running, JobStatus.FAILED, null, e.toString(), advanceSchedule)
            }

        val finalExec = finishExecution(job, running, JobStatus.SUCCESS, output, null, advanceSchedule)
        dbQuery { execRepo.pruneExceeding(job.id, executor.historyRetention) }
        return finalExec
    }

    private suspend fun finishExecution(
        job: ScheduledJob,
        running: JobExecution,
        status: JobStatus,
        resultMessage: String?,
        errorMessage: String?,
        advanceSchedule: Boolean,
    ): JobExecution {
        val finishedAt = clock()
        dbQuery { execRepo.markFinished(running.id, finishedAt, status, resultMessage, errorMessage) }
        if (advanceSchedule) {
            advanceNextRun(job, status)
        } else {
            // Manual run: record the outcome so the job card agrees with the executions list.
            dbQuery { jobRepo.recordLastRun(job.id, finishedAt, status.name) }
        }
        return running.copy(finishedAt = finishedAt, status = status, resultMessage = resultMessage, errorMessage = errorMessage)
    }

    private suspend fun advanceNextRun(
        job: ScheduledJob,
        status: JobStatus,
    ) {
        val zone = runCatching { ZoneId.of(job.timezone) }.getOrDefault(ZoneId.of("Europe/Berlin"))
        val next = calculator.next(job.frequency, job.timeOfDay, job.dayOfWeek, job.dayOfMonth, zone, clock())
        dbQuery { jobRepo.updateScheduleState(job.id, next, clock(), status.name) }
    }

    @TestOnlyApi
    suspend fun recoverOrphans() {
        val orphanCount = dbQuery { execRepo.markOrphanRunningAsFailed("interrupted by server restart") }
        if (orphanCount > 0) logger.warn("recovered $orphanCount orphan RUNNING executions")

        val now = clock()
        val missed = dbQuery { jobRepo.findDue(now) }
        missed.forEach { job ->
            dbQuery {
                execRepo.insertSkipped(job.id, TriggerSource.SCHEDULED, now, "missed while server was down")
            }
            advanceNextRun(job, JobStatus.SKIPPED)
        }
        if (missed.isNotEmpty()) logger.info("marked ${missed.size} missed runs as SKIPPED on boot")
    }
}
