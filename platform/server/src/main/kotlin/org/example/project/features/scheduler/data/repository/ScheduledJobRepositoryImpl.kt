package org.example.project.features.scheduler.data.repository

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.enums.scheduler.JobFrequency
import org.example.project.core.domain.enums.scheduler.JobStatus
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.features.scheduler.database.tables.ScheduledJobEntity
import org.example.project.features.scheduler.database.tables.ScheduledJobs
import org.example.project.features.scheduler.domain.models.ScheduledJob
import org.example.project.features.scheduler.domain.repository.ScheduledJobRepository
import org.jetbrains.exposed.v1.core.*
import java.time.Instant
import java.util.UUID

@OptIn(kotlin.time.ExperimentalTime::class)
class ScheduledJobRepositoryImpl : ScheduledJobRepository {
    override fun findAll(): List<ScheduledJob> = ScheduledJobEntity.all().orderBy(ScheduledJobs.createdAt to SortOrder.ASC).map { it.toModel() }

    override fun findById(id: UUID): ScheduledJob? = ScheduledJobEntity.findById(id)?.toModel()

    override fun findByType(type: ScheduledJobType): ScheduledJob? =
        ScheduledJobEntity
            .find { ScheduledJobs.type eq type.name }
            .limit(1)
            .firstOrNull()
            ?.toModel()

    override fun findDue(now: Instant): List<ScheduledJob> {
        val nowKt = now.toKotlinxLocalDateTime()
        return ScheduledJobEntity
            .find {
                (ScheduledJobs.enabled eq true) and (ScheduledJobs.nextRunAt lessEq nowKt)
            }.orderBy(ScheduledJobs.nextRunAt to SortOrder.ASC)
            .map { it.toModel() }
    }

    override fun create(job: ScheduledJob): ScheduledJob {
        val e = ScheduledJobEntity.new(job.id) { applyFrom(job) }
        return e.toModel()
    }

    override fun update(job: ScheduledJob): ScheduledJob {
        val e =
            ScheduledJobEntity.findById(job.id)
                ?: throw NoSuchElementException("ScheduledJob not found: ${job.id}")
        e.applyFrom(job)
        e.updatedAt = Instant.now().toKotlinxLocalDateTime()
        return e.toModel()
    }

    override fun delete(id: UUID): Boolean {
        val e = ScheduledJobEntity.findById(id) ?: return false
        e.delete()
        return true
    }

    override fun updateScheduleState(
        id: UUID,
        nextRunAt: Instant,
        lastRunAt: Instant?,
        lastStatus: String?,
    ) {
        val e =
            ScheduledJobEntity.findById(id)
                ?: throw NoSuchElementException("ScheduledJob not found: $id")
        e.nextRunAt = nextRunAt.toKotlinxLocalDateTime()
        if (lastRunAt != null) e.lastRunAt = lastRunAt.toKotlinxLocalDateTime()
        if (lastStatus != null) e.lastStatus = lastStatus
        e.updatedAt = Instant.now().toKotlinxLocalDateTime()
    }

    override fun recordLastRun(
        id: UUID,
        lastRunAt: Instant,
        lastStatus: String,
    ) {
        val e =
            ScheduledJobEntity.findById(id)
                ?: throw NoSuchElementException("ScheduledJob not found: $id")
        e.lastRunAt = lastRunAt.toKotlinxLocalDateTime()
        e.lastStatus = lastStatus
        e.updatedAt = Instant.now().toKotlinxLocalDateTime()
    }

    private fun ScheduledJobEntity.applyFrom(job: ScheduledJob) {
        name = job.name
        type = job.type.name
        enabled = job.enabled
        frequency = job.frequency.name
        timeOfDay = job.timeOfDay
        dayOfWeek = job.dayOfWeek
        dayOfMonth = job.dayOfMonth
        parametersJson = job.parametersJson
        timezone = job.timezone
        nextRunAt = job.nextRunAt.toKotlinxLocalDateTime()
        lastRunAt = job.lastRunAt?.toKotlinxLocalDateTime()
        lastStatus = job.lastStatus?.name
        createdAt = job.createdAt.toKotlinxLocalDateTime()
    }

    private fun ScheduledJobEntity.toModel() =
        ScheduledJob(
            id = id.value,
            name = name,
            type = ScheduledJobType.valueOf(type),
            enabled = enabled,
            frequency = JobFrequency.valueOf(frequency),
            timeOfDay = timeOfDay,
            dayOfWeek = dayOfWeek,
            dayOfMonth = dayOfMonth,
            parametersJson = parametersJson,
            timezone = timezone,
            nextRunAt = nextRunAt.toJavaInstant(),
            lastRunAt = lastRunAt?.toJavaInstant(),
            lastStatus = lastStatus?.let { JobStatus.valueOf(it) },
            createdAt = createdAt.toJavaInstant(),
            updatedAt = updatedAt.toJavaInstant(),
        )

    // Conversion helpers

    private fun Instant.toKotlinxLocalDateTime(): LocalDateTime =
        kotlinx.datetime.Instant
            .fromEpochMilliseconds(toEpochMilli())
            .toLocalDateTime(TimeZone.UTC)

    private fun LocalDateTime.toJavaInstant(): Instant =
        java.time.Instant.ofEpochMilli(
            toInstant(TimeZone.UTC).toEpochMilliseconds(),
        )
}
