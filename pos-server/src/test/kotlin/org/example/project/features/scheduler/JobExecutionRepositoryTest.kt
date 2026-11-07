package org.example.project.features.scheduler

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.enums.scheduler.JobStatus
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.core.domain.enums.scheduler.TriggerSource
import org.example.project.features.scheduler.data.repository.JobExecutionRepositoryImpl
import org.example.project.features.scheduler.database.tables.JobExecutionEntity
import org.example.project.features.scheduler.database.tables.ScheduledJobEntity
import org.example.project.features.scheduler.database.tables.ScheduledJobs
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The self-exclusion in `countRunningExcludingType`, which the update preflight depends on.
 *
 * Without it the nightly update check counts its own RUNNING row — inserted by `SchedulerEngine`
 * before it calls the executor — and refuses the update with a perfectly plausible `JOB_RUNNING`
 * every single night, forever, with nothing in the log that looks wrong.
 */
@DisplayName("JobExecutionRepository.countRunningExcludingType")
class JobExecutionRepositoryTest {
    private val repo = JobExecutionRepositoryImpl()

    @BeforeEach
    fun setUp() = TestDatabase.setup()

    @AfterEach
    fun tearDown() = TestDatabase.teardown()

    private fun seedJob(type: ScheduledJobType): UUID {
        val id = UUID.randomUUID()
        val now =
            kotlinx.datetime.Instant
                .fromEpochMilliseconds(Instant.now().toEpochMilli())
                .toLocalDateTime(TimeZone.UTC)
        transaction {
            ScheduledJobEntity.new(id) {
                name = type.name
                this.type = type.name
                enabled = true
                frequency = "DAILY"
                timeOfDay = "03:00"
                dayOfWeek = null
                dayOfMonth = null
                parametersJson = "{}"
                timezone = "Europe/Berlin"
                nextRunAt = now
                createdAt = now
                updatedAt = now
            }
        }
        return id
    }

    private fun seedRunning(jobId: UUID) {
        val now =
            kotlinx.datetime.Instant
                .fromEpochMilliseconds(Instant.now().toEpochMilli())
                .toLocalDateTime(TimeZone.UTC)
        transaction {
            JobExecutionEntity.new(UUID.randomUUID()) {
                this.jobId = EntityID(jobId, ScheduledJobs)
                startedAt = now
                finishedAt = null
                status = JobStatus.RUNNING.name
                triggerSource = TriggerSource.SCHEDULED.name
                resultMessage = null
                errorMessage = null
            }
        }
    }

    @Test
    fun `the excluded job's own running execution is not counted`() {
        val updateCheck = seedJob(ScheduledJobType.UPDATE_CHECK)
        seedRunning(updateCheck)

        val count = transaction { repo.countRunningExcludingType(ScheduledJobType.UPDATE_CHECK) }

        assertEquals(0, count, "the update check must not see its own RUNNING row")
    }

    @Test
    fun `another job's running execution is still counted`() {
        val updateCheck = seedJob(ScheduledJobType.UPDATE_CHECK)
        val backup = seedJob(ScheduledJobType.BACKUP)
        seedRunning(updateCheck)
        seedRunning(backup)

        val count = transaction { repo.countRunningExcludingType(ScheduledJobType.UPDATE_CHECK) }

        assertEquals(1, count, "a concurrently running backup must still block an update")
    }

    @Test
    fun `a finished execution of another job is not counted`() {
        seedJob(ScheduledJobType.UPDATE_CHECK)
        val backup = seedJob(ScheduledJobType.BACKUP)
        seedRunning(backup)
        transaction {
            JobExecutionEntity.all().forEach { it.status = JobStatus.SUCCESS.name }
        }

        val count = transaction { repo.countRunningExcludingType(ScheduledJobType.UPDATE_CHECK) }

        assertEquals(0, count)
    }
}
