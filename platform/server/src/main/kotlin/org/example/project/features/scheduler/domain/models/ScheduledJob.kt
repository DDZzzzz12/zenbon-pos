package org.example.project.features.scheduler.domain.models

import org.example.project.core.domain.enums.scheduler.JobFrequency
import org.example.project.core.domain.enums.scheduler.JobStatus
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import java.time.Instant
import java.util.UUID

data class ScheduledJob(
    val id: UUID,
    val name: String,
    val type: ScheduledJobType,
    val enabled: Boolean,
    val frequency: JobFrequency,
    val timeOfDay: String,
    val dayOfWeek: Int?,
    val dayOfMonth: Int?,
    val parametersJson: String,
    val timezone: String,
    val nextRunAt: Instant,
    val lastRunAt: Instant?,
    val lastStatus: JobStatus?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
