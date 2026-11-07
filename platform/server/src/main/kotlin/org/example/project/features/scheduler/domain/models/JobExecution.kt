package org.example.project.features.scheduler.domain.models

import org.example.project.core.domain.enums.scheduler.JobStatus
import org.example.project.core.domain.enums.scheduler.TriggerSource
import java.time.Instant
import java.util.UUID

data class JobExecution(
    val id: UUID,
    val jobId: UUID,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val status: JobStatus,
    val triggerSource: TriggerSource,
    val resultMessage: String?,
    val errorMessage: String?,
)
