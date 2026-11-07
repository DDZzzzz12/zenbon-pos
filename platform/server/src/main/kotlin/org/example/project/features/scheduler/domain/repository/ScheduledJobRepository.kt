package org.example.project.features.scheduler.domain.repository

import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.features.scheduler.domain.models.ScheduledJob
import java.time.Instant
import java.util.UUID

interface ScheduledJobRepository {
    fun findAll(): List<ScheduledJob>

    fun findById(id: UUID): ScheduledJob?

    fun findByType(type: ScheduledJobType): ScheduledJob?

    fun findDue(now: Instant): List<ScheduledJob>

    fun create(job: ScheduledJob): ScheduledJob

    fun update(job: ScheduledJob): ScheduledJob

    fun delete(id: UUID): Boolean

    fun updateScheduleState(
        id: UUID,
        nextRunAt: Instant,
        lastRunAt: Instant?,
        lastStatus: String?,
    )

    /** Records the outcome of a finished run without touching `nextRunAt` (manual runs). */
    fun recordLastRun(
        id: UUID,
        lastRunAt: Instant,
        lastStatus: String,
    )
}
