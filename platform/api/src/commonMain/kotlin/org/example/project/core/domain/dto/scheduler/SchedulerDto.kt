package org.example.project.core.domain.dto.scheduler

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.scheduler.JobFrequency
import org.example.project.core.domain.enums.scheduler.JobStatus
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.core.domain.enums.scheduler.TriggerSource

object SchedulerDto {
    @Serializable
    data class ScheduledJobResponseDto(
        val id: String,
        val name: String,
        val type: ScheduledJobType,
        val enabled: Boolean,
        val frequency: JobFrequency,
        val timeOfDay: String,
        val dayOfWeek: Int? = null,
        val dayOfMonth: Int? = null,
        val parametersJson: String,
        val timezone: String,
        val nextRunAt: Long,
        val lastRunAt: Long? = null,
        val lastStatus: JobStatus? = null,
        val createdAt: Long,
        val updatedAt: Long,
    )

    @Serializable
    data class CreateScheduledJobRequestDto(
        val name: String,
        val type: ScheduledJobType,
        val enabled: Boolean = true,
        val frequency: JobFrequency,
        val timeOfDay: String,
        val dayOfWeek: Int? = null,
        val dayOfMonth: Int? = null,
        val parametersJson: String = "{}",
        val timezone: String = "Europe/Berlin",
    )

    @Serializable
    data class UpdateScheduledJobRequestDto(
        val name: String? = null,
        val enabled: Boolean? = null,
        val frequency: JobFrequency? = null,
        val timeOfDay: String? = null,
        val dayOfWeek: Int? = null,
        val dayOfMonth: Int? = null,
        val parametersJson: String? = null,
        val timezone: String? = null,
    )

    @Serializable
    data class SetJobEnabledRequestDto(
        val enabled: Boolean,
    )

    @Serializable
    data class JobExecutionResponseDto(
        val id: String,
        val jobId: String,
        val startedAt: Long,
        val finishedAt: Long? = null,
        val status: JobStatus,
        val triggerSource: TriggerSource,
        val resultMessage: String? = null,
        val errorMessage: String? = null,
    )

    @Serializable
    data class ScheduledJobDetailDto(
        val job: ScheduledJobResponseDto,
        val recentExecutions: List<JobExecutionResponseDto>,
    )

    @Serializable
    data class JobExecutionPageDto(
        val items: List<JobExecutionResponseDto>,
        val total: Long,
        val limit: Int,
        val offset: Int,
    )

    @Serializable
    data class JobTypeInfoDto(
        val type: ScheduledJobType,
        val labelKey: String,
        val historyRetention: Int,
        val parameterSchema: String,
    )

    /** Parameters for BACKUP job. Kept in shared so client can render form. */
    @Serializable
    data class BackupJobParams(
        val savePath: String? = null,
        val retentionCount: Int = 7,
    )
}
