package org.example.project.features.scheduler

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.example.project.core.domain.enums.scheduler.JobFrequency
import org.example.project.core.domain.enums.scheduler.JobStatus
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.features.scheduler.data.repository.JobExecutionRepositoryImpl
import org.example.project.features.scheduler.data.repository.ScheduledJobRepositoryImpl
import org.example.project.features.scheduler.data.service.SchedulerEngine
import org.example.project.features.scheduler.database.tables.JobExecutionEntity
import org.example.project.features.scheduler.database.tables.JobExecutions
import org.example.project.features.scheduler.database.tables.ScheduledJobEntity
import org.example.project.features.scheduler.domain.models.ScheduledJob
import org.example.project.features.scheduler.domain.service.JobPartiallyFailedException
import org.example.project.features.scheduler.domain.service.ScheduledJobExecutor
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * admin16 NOTE-7: the DATEV auto-export failed one Kassenbuch period and the run was recorded as
 * SUCCESS, so the admin screen said "Status: SUCCESS". A run that did only part of its work is now
 * recorded as FAILED — on the execution row and as the job's last status — keeping what did work as
 * the result and naming what failed as the error.
 */
@DisplayName("SchedulerEngine records a partly failed run as FAILED")
class SchedulerPartialFailureTest {
    private val partlyFailing =
        object : ScheduledJobExecutor {
            override val type = ScheduledJobType.DATEV_AUTO_EXPORT
            override val historyRetention = 10
            override val labelKey = "TEST"

            override suspend fun execute(parametersJson: String): String =
                throw JobPartiallyFailedException(
                    resultMessage = "KASSENBUCH: 0 periods, 1 failed, AP_LEDGER: 2 periods",
                    failedParts = listOf("KASSENBUCH 2026-09: disk full"),
                )

            override fun validateParameters(parametersJson: String) {}

            override fun parameterSchema(): JsonElement = JsonObject(emptyMap())
        }

    private lateinit var engine: SchedulerEngine

    @BeforeEach
    fun setUp() {
        TestDatabase.setup()
        engine = SchedulerEngine(ScheduledJobRepositoryImpl(), JobExecutionRepositoryImpl(), listOf(partlyFailing))
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.teardown()
    }

    @Test
    fun `run now records FAILED with the done work as result and the failed parts as error`() {
        val job = seedDueJob()

        val execution = runBlocking { engine.runNow(job) }

        assertEquals(JobStatus.FAILED, execution.status)
        assertEquals("KASSENBUCH: 0 periods, 1 failed, AP_LEDGER: 2 periods", execution.resultMessage)
        assertEquals("Failed: KASSENBUCH 2026-09: disk full", execution.errorMessage)
        val row = transaction { JobExecutionEntity.find { JobExecutions.jobId eq job.id }.single() }
        assertEquals(JobStatus.FAILED.name, row.status)
        assertEquals(execution.errorMessage, row.errorMessage)
    }

    @Test
    fun `a scheduled run leaves FAILED as the job's last status`() {
        val job = seedDueJob()

        runBlocking { engine.processDueJobs() }

        assertEquals(JobStatus.FAILED.name, awaitLastStatus(job.id))
    }

    private fun awaitLastStatus(jobId: UUID): String {
        repeat(50) {
            val status = transaction { ScheduledJobEntity.findById(jobId)!!.lastStatus }
            if (status != null) return status
            Thread.sleep(100)
        }
        error("job $jobId recorded no last status")
    }

    private fun seedDueJob(): ScheduledJob {
        val now = Instant.now()
        val job =
            ScheduledJob(
                id = UUID.randomUUID(),
                name = "DATEV auto export",
                type = ScheduledJobType.DATEV_AUTO_EXPORT,
                enabled = true,
                frequency = JobFrequency.DAILY,
                timeOfDay = "03:00",
                dayOfWeek = null,
                dayOfMonth = null,
                parametersJson = "{}",
                timezone = "Europe/Berlin",
                nextRunAt = now.minusSeconds(3600),
                lastRunAt = null,
                lastStatus = null,
                createdAt = now,
                updatedAt = now,
            )
        transaction {
            ScheduledJobEntity.new(job.id) {
                name = job.name
                type = job.type.name
                enabled = job.enabled
                frequency = job.frequency.name
                timeOfDay = job.timeOfDay
                dayOfWeek = null
                dayOfMonth = null
                parametersJson = job.parametersJson
                timezone = job.timezone
                nextRunAt = job.nextRunAt.toUtcLocal()
                lastRunAt = null
                lastStatus = null
                createdAt = job.createdAt.toUtcLocal()
                updatedAt = job.updatedAt.toUtcLocal()
            }
        }
        assertTrue(transaction { ScheduledJobEntity.findById(job.id) } != null)
        return job
    }

    private fun Instant.toUtcLocal() =
        kotlinx.datetime.Instant
            .fromEpochMilliseconds(toEpochMilli())
            .toLocalDateTime(TimeZone.UTC)
}
