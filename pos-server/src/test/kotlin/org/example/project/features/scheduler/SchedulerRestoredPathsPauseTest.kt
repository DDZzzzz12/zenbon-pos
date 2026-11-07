package org.example.project.features.scheduler

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.example.project.core.domain.enums.scheduler.JobFrequency
import org.example.project.core.domain.enums.scheduler.JobStatus
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.error.PosErrorCode
import org.example.project.features.scheduler.data.repository.JobExecutionRepositoryImpl
import org.example.project.features.scheduler.data.repository.ScheduledJobRepositoryImpl
import org.example.project.features.scheduler.data.service.SchedulerEngine
import org.example.project.features.scheduler.data.service.executors.BackupJobExecutor
import org.example.project.features.scheduler.data.service.executors.DatevAutoExportJobExecutor
import org.example.project.features.scheduler.database.tables.JobExecutionEntity
import org.example.project.features.scheduler.database.tables.JobExecutions
import org.example.project.features.scheduler.database.tables.ScheduledJobEntity
import org.example.project.features.scheduler.domain.models.ScheduledJob
import org.example.project.features.scheduler.domain.service.ScheduledJobExecutor
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The scheduler half of the post-restore pause: a job whose executor writes to a restored path of
 * another installation is skipped — not run, nothing written — until that path is reviewed, and a
 * manual "run now" is refused with 409.
 *
 * The executor here stands in for the DATEV catch-up: it writes a file into the folder the job would
 * use, so "did not run" is asserted on the filesystem, not only on the execution row.
 */
@DisplayName("SchedulerEngine pauses jobs that write to restored paths awaiting review")
class SchedulerRestoredPathsPauseTest {
    private val exportDir: File = Files.createTempDirectory("restored-paths-export").toFile()
    private var pending: Set<RestoredPathSetting> = emptySet()

    private val writingExecutor =
        object : ScheduledJobExecutor {
            override val type = ScheduledJobType.DATEV_AUTO_EXPORT
            override val historyRetention = 10
            override val labelKey = "TEST"
            override val restoredPathsWritten = setOf(RestoredPathSetting.DATEV_EXPORT_PATH)

            override suspend fun execute(parametersJson: String): String {
                File(exportDir, "export-${UUID.randomUUID()}.zip").writeText("exported")
                return "wrote one file"
            }

            override fun validateParameters(parametersJson: String) {}

            override fun parameterSchema(): JsonElement = JsonObject(emptyMap())
        }

    private lateinit var engine: SchedulerEngine

    @BeforeEach
    fun setUp() {
        TestDatabase.setup()
        engine =
            SchedulerEngine(
                ScheduledJobRepositoryImpl(),
                JobExecutionRepositoryImpl(),
                listOf(writingExecutor),
                pendingRestoredPaths = { pending },
            )
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.teardown()
        exportDir.deleteRecursively()
    }

    @Test
    fun `a due DATEV export is skipped while its restored path awaits review and writes nothing`() {
        pending = setOf(RestoredPathSetting.DATEV_EXPORT_PATH)
        val job = seedDueJob()

        runBlocking { engine.processDueJobs() }
        val statuses = awaitExecutions(job.id)

        assertEquals(listOf(JobStatus.SKIPPED.name), statuses, "the paused run must be recorded as SKIPPED only")
        assertTrue(exportDir.listFiles().orEmpty().isEmpty(), "nothing may be written while the path is paused")
        val skipped = transaction { JobExecutionEntity.find { JobExecutions.jobId eq job.id }.single() }
        assertTrue(skipped.errorMessage.orEmpty().contains("restored paths need review"), "reason: ${skipped.errorMessage}")
        assertTrue(nextRunOf(job.id) > Instant.now(), "the schedule must move on rather than retry every tick")
    }

    @Test
    fun `a pending path the job does not write to does not hold it`() {
        pending = setOf(RestoredPathSetting.BACKUP_PATH)
        val job = seedDueJob()

        runBlocking { engine.processDueJobs() }

        assertEquals(listOf(JobStatus.SUCCESS.name), awaitExecutions(job.id))
        assertEquals(1, exportDir.listFiles().orEmpty().size)
    }

    @Test
    fun `once the review is resolved the job runs again`() {
        pending = emptySet()
        val job = seedDueJob()

        runBlocking { engine.processDueJobs() }

        assertEquals(listOf(JobStatus.SUCCESS.name), awaitExecutions(job.id))
        assertEquals(1, exportDir.listFiles().orEmpty().size, "the export runs as before")
    }

    @Test
    fun `run now is refused with 409 RESTORED_PATHS_NEED_REVIEW while paused`() {
        pending = setOf(RestoredPathSetting.DATEV_EXPORT_PATH)
        val job = seedDueJob()

        val refusal = assertFailsWith<PosException> { runBlocking { engine.runNow(job) } }

        assertEquals(PosErrorCode.RESTORED_PATHS_NEED_REVIEW, refusal.code)
        assertEquals(409, refusal.httpStatus.value)
        assertTrue(exportDir.listFiles().orEmpty().isEmpty())
        assertTrue(transaction { JobExecutionEntity.find { JobExecutions.jobId eq job.id }.empty() }, "no execution row")
    }

    @Test
    fun `the real file-writing executors declare the paths they write to`() {
        // Constructed only to read the declaration; execute() is never called.
        val datev = DatevAutoExportJobExecutor(catchup = unused(), config = unused(), systemUserId = UUID.randomUUID())
        val backup = BackupJobExecutor(backupService = unused(), config = unused())
        assertEquals(setOf(RestoredPathSetting.DATEV_EXPORT_PATH), datev.restoredPathsWritten)
        assertEquals(setOf(RestoredPathSetting.BACKUP_PATH), backup.restoredPathsWritten)
    }

    private inline fun <reified T : Any> unused(): T =
        java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("${method.name} must not be called")
        } as T

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
        return job
    }

    /** processDueJobs dispatches asynchronously; wait until the run has finished (not RUNNING). */
    private fun awaitExecutions(jobId: UUID): List<String> {
        repeat(50) {
            val statuses =
                transaction { JobExecutionEntity.find { JobExecutions.jobId eq jobId }.map { it.status } }
            if (statuses.isNotEmpty() && JobStatus.RUNNING.name !in statuses) return statuses
            Thread.sleep(100)
        }
        error("job $jobId did not finish")
    }

    private fun nextRunOf(jobId: UUID): Instant =
        transaction { ScheduledJobEntity.findById(jobId)!!.nextRunAt }
            .let { java.time.LocalDateTime.of(it.year, it.monthNumber, it.dayOfMonth, it.hour, it.minute, it.second) }
            .atZone(java.time.ZoneOffset.UTC)
            .toInstant()

    private fun Instant.toUtcLocal() =
        kotlinx.datetime.Instant
            .fromEpochMilliseconds(toEpochMilli())
            .toLocalDateTime(TimeZone.UTC)
}
