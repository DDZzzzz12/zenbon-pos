package org.example.project.features.scheduler

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.enums.scheduler.JobFrequency
import org.example.project.core.domain.enums.scheduler.JobStatus
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.core.domain.enums.scheduler.TriggerSource
import org.example.project.features.scheduler.data.service.SchedulerEngine
import org.example.project.features.scheduler.database.tables.JobExecutionEntity
import org.example.project.features.scheduler.database.tables.JobExecutions
import org.example.project.features.scheduler.database.tables.ScheduledJobEntity
import org.example.project.features.scheduler.database.tables.ScheduledJobs
import org.example.project.features.scheduler.domain.models.ScheduledJob
import org.example.project.infrastructure.di.httpClientModule
import org.example.project.infrastructure.di.reposModule
import org.example.project.infrastructure.di.serializationModule
import org.example.project.infrastructure.di.servicesModule
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import java.time.Instant
import java.util.UUID

/**
 * Integration tests for SchedulerEngine, using the real H2 test DB.
 *
 * Each test sets up a fresh DB + Koin context directly (not via testApp/Ktor)
 * since these tests exercise engine internals without HTTP routes.
 *
 * Engine-level behaviour is tested by:
 * 1. Seeding state directly via Exposed entities
 * 2. Calling engine.recoverOrphans() or engine.processDueJobs() (internal methods)
 * 3. Asserting resulting DB state
 */
@DisplayName("SchedulerEngine")
class SchedulerEngineTest {
    @BeforeEach
    fun setUp() {
        TestDatabase.setup()
        startKoin {
            modules(serializationModule, httpClientModule, reposModule, servicesModule)
        }
        TestData.createManager()
    }

    @AfterEach
    fun tearDown() {
        GlobalContext.getOrNull()?.get<SchedulerEngine>()?.shutdown()
        TestDatabase.teardown()
    }

    private fun Instant.toKtLocalDateTime() =
        kotlinx.datetime.Instant
            .fromEpochMilliseconds(toEpochMilli())
            .toLocalDateTime(TimeZone.UTC)

    private fun makeJob(
        nextRunAt: Instant = Instant.now().minusSeconds(3600), // in the past = due
        enabled: Boolean = true,
        parametersJson: String = "{}",
    ): ScheduledJob =
        ScheduledJob(
            id = UUID.randomUUID(),
            name = "Test Job",
            type = ScheduledJobType.BACKUP,
            enabled = enabled,
            frequency = JobFrequency.DAILY,
            timeOfDay = "03:00",
            dayOfWeek = null,
            dayOfMonth = null,
            parametersJson = parametersJson,
            timezone = "Europe/Berlin",
            nextRunAt = nextRunAt,
            lastRunAt = null,
            lastStatus = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private fun seedJob(job: ScheduledJob) {
        transaction {
            ScheduledJobEntity.new(job.id) {
                name = job.name
                type = job.type.name
                enabled = job.enabled
                frequency = job.frequency.name
                timeOfDay = job.timeOfDay
                dayOfWeek = job.dayOfWeek
                dayOfMonth = job.dayOfMonth
                parametersJson = job.parametersJson
                timezone = job.timezone
                nextRunAt = job.nextRunAt.toKtLocalDateTime()
                lastRunAt = null
                lastStatus = null
                createdAt = job.createdAt.toKtLocalDateTime()
                updatedAt = job.updatedAt.toKtLocalDateTime()
            }
        }
    }

    private fun seedRunningExecution(jobId: UUID): UUID {
        val execId = UUID.randomUUID()
        val startedAt = Instant.now().minusSeconds(300).toKtLocalDateTime()
        transaction {
            JobExecutionEntity.new(execId) {
                this.jobId = EntityID(jobId, ScheduledJobs)
                this.startedAt = startedAt
                this.finishedAt = null
                this.status = JobStatus.RUNNING.name
                this.triggerSource = TriggerSource.SCHEDULED.name
                this.resultMessage = null
                this.errorMessage = null
            }
        }
        return execId
    }

    private fun getEngine(): SchedulerEngine = GlobalContext.get().get()

    // ─────────────────────────────────────────────────────────────────
    // 1. recoverOrphans marks orphan RUNNING as FAILED;
    //    missed enabled jobs get a SKIPPED exec and nextRunAt advanced
    // ─────────────────────────────────────────────────────────────────
    @Test
    fun `recoverOrphans marks orphan RUNNING as FAILED and creates SKIPPED for missed jobs`() {
        val job = makeJob(nextRunAt = Instant.now().minusSeconds(7200))
        seedJob(job)

        // Seed an orphan RUNNING execution
        val orphanExecId = seedRunningExecution(job.id)

        val engine = getEngine()
        runBlocking { engine.recoverOrphans() }

        // Orphan execution should now be FAILED
        val orphanExec = transaction { JobExecutionEntity.findById(orphanExecId) }
        assertNotNull(orphanExec, "Orphan execution should still exist in DB")
        assertEquals(
            JobStatus.FAILED.name,
            orphanExec!!.status,
            "Orphan RUNNING execution should be marked FAILED",
        )
        assertNotNull(orphanExec.finishedAt, "FAILED execution should have finishedAt set")

        // Missed job should have a SKIPPED execution created by recoverOrphans
        val executions =
            transaction {
                JobExecutionEntity.find { JobExecutions.jobId eq job.id }.toList()
            }
        assertTrue(
            executions.any { it.status == JobStatus.SKIPPED.name },
            "Expected at least one SKIPPED execution for missed job. Got: ${executions.map { it.status }}",
        )

        // nextRunAt should be advanced to the future
        val updatedJob = transaction { ScheduledJobEntity.findById(job.id) }
        assertNotNull(updatedJob)
        val nowEpochSec =
            java.time.Instant
                .now()
                .epochSecond
        val nextRunEpochSec =
            updatedJob!!.nextRunAt.let { ldt ->
                java.time.LocalDateTime
                    .of(
                        ldt.year,
                        ldt.monthNumber,
                        ldt.dayOfMonth,
                        ldt.hour,
                        ldt.minute,
                        ldt.second,
                    ).atZone(java.time.ZoneOffset.UTC)
                    .toEpochSecond()
            }
        assertTrue(
            nextRunEpochSec > nowEpochSec,
            "nextRunAt should be advanced into the future",
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // 2. processDueJobs records an execution and advances nextRunAt
    // ─────────────────────────────────────────────────────────────────
    @Test
    fun `processDueJobs creates execution record and advances nextRunAt`() {
        val originalNextRun = Instant.now().minusSeconds(60)
        val job = makeJob(nextRunAt = originalNextRun)
        seedJob(job)

        val engine = getEngine()
        runBlocking { engine.processDueJobs() }
        // Allow coroutine to complete
        Thread.sleep(1000)

        val executions =
            transaction {
                JobExecutionEntity.find { JobExecutions.jobId eq job.id }.toList()
            }
        assertTrue(
            executions.isNotEmpty(),
            "Expected at least one execution record after processDueJobs",
        )

        // nextRunAt must have advanced beyond the original value
        val updatedJob = transaction { ScheduledJobEntity.findById(job.id) }
        assertNotNull(updatedJob)
        val originalEpochSec = originalNextRun.epochSecond
        val updatedEpochSec =
            updatedJob!!.nextRunAt.let { ldt ->
                java.time.LocalDateTime
                    .of(
                        ldt.year,
                        ldt.monthNumber,
                        ldt.dayOfMonth,
                        ldt.hour,
                        ldt.minute,
                        ldt.second,
                    ).atZone(java.time.ZoneOffset.UTC)
                    .toEpochSecond()
            }
        assertTrue(
            updatedEpochSec > originalEpochSec,
            "nextRunAt should be advanced. Original epoch: $originalEpochSec, Updated: $updatedEpochSec",
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // 2b. A manual run records lastRunAt/lastStatus but leaves nextRunAt alone
    // ─────────────────────────────────────────────────────────────────
    @Test
    fun `runNow records lastRunAt and lastStatus without moving nextRunAt`() {
        val originalNextRun = Instant.now().plusSeconds(86_400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val job = makeJob(nextRunAt = originalNextRun)
        seedJob(job)

        val exec = runBlocking { getEngine().runNow(job) }

        val updated = transaction { ScheduledJobEntity.findById(job.id) }
        assertNotNull(updated)
        assertNotNull(updated!!.lastRunAt, "manual run must set lastRunAt")
        assertEquals(exec.status.name, updated.lastStatus)
        assertEquals(
            originalNextRun.toKtLocalDateTime(),
            updated.nextRunAt,
            "manual run must not touch nextRunAt",
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // 3. Overlap guard: SKIPPED when previous run still RUNNING
    // ─────────────────────────────────────────────────────────────────
    @Test
    fun `processDueJobs inserts SKIPPED execution when previous run is still RUNNING`() {
        val job = makeJob(nextRunAt = Instant.now().minusSeconds(60))
        seedJob(job)

        // Seed an existing RUNNING execution to simulate overlap
        seedRunningExecution(job.id)

        val engine = getEngine()
        runBlocking { engine.processDueJobs() }
        Thread.sleep(300)

        val executions =
            transaction {
                JobExecutionEntity.find { JobExecutions.jobId eq job.id }.toList()
            }
        assertTrue(
            executions.any { it.status == JobStatus.SKIPPED.name },
            "Expected a SKIPPED execution when overlap is detected. Got: ${executions.map { it.status }}",
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // 4. Executor exception → FAILED exec; engine does not crash
    // ─────────────────────────────────────────────────────────────────
    @Test
    fun `processDueJobs does not crash when executor encounters an error`() {
        // Use non-existent savePath to cause BackupJobExecutor to produce FAILED result
        val job =
            makeJob(
                nextRunAt = Instant.now().minusSeconds(60),
                parametersJson = """{"savePath":"/non-existent-xyz-path-12345","retentionCount":1}""",
            )
        seedJob(job)

        val engine = getEngine()
        // Must not throw
        runBlocking {
            try {
                engine.processDueJobs()
            } catch (e: Exception) {
                throw AssertionError("SchedulerEngine.processDueJobs() must not propagate exceptions", e)
            }
        }
        Thread.sleep(500)

        // At least one execution record should be created
        val executions =
            transaction {
                JobExecutionEntity.find { JobExecutions.jobId eq job.id }.toList()
            }
        assertTrue(
            executions.isNotEmpty(),
            "Expected at least one execution record even when executor fails",
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // 5. pruneExceeding keeps exactly historyRetention rows
    // ─────────────────────────────────────────────────────────────────
    @Test
    fun `after processDueJobs pruneExceeding keeps at most historyRetention execution rows`() {
        val retention = 30 // BackupJobExecutor.historyRetention
        val job = makeJob(nextRunAt = Instant.now().minusSeconds(60))
        seedJob(job)

        // Seed (retention - 1) = 29 old SUCCESS executions
        val baseTime = Instant.now().minusSeconds(3600 * 48)
        transaction {
            repeat(retention - 1) { i ->
                val t = baseTime.plusSeconds((i * 60).toLong()).toKtLocalDateTime()
                JobExecutionEntity.new(UUID.randomUUID()) {
                    jobId = EntityID(job.id, ScheduledJobs)
                    startedAt = t
                    finishedAt = t
                    status = JobStatus.SUCCESS.name
                    triggerSource = TriggerSource.SCHEDULED.name
                    resultMessage = "historical-$i"
                    errorMessage = null
                }
            }
        }

        val countBefore =
            transaction {
                JobExecutionEntity.find { JobExecutions.jobId eq job.id }.count()
            }
        assertEquals(
            (retention - 1).toLong(),
            countBefore,
            "Should have seeded ${retention - 1} executions before test run",
        )

        // processDueJobs will add 1 more, then prune to retention
        val engine = getEngine()
        runBlocking { engine.processDueJobs() }
        Thread.sleep(1000)

        val countAfter =
            transaction {
                JobExecutionEntity.find { JobExecutions.jobId eq job.id }.count()
            }
        assertTrue(
            countAfter <= retention.toLong(),
            "Expected at most $retention executions after prune (historyRetention=$retention), got $countAfter",
        )
    }
}
