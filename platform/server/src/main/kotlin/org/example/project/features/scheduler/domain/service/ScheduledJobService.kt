package org.example.project.features.scheduler.domain.service

import org.example.project.core.domain.dto.scheduler.SchedulerDto
import java.util.UUID

interface ScheduledJobService {
    suspend fun list(): List<SchedulerDto.ScheduledJobResponseDto>

    suspend fun getDetail(id: UUID): SchedulerDto.ScheduledJobDetailDto

    suspend fun triggerManualRun(id: UUID): SchedulerDto.JobExecutionResponseDto

    suspend fun setEnabled(
        id: UUID,
        enabled: Boolean,
    ): SchedulerDto.ScheduledJobResponseDto

    suspend fun listExecutions(
        id: UUID,
        limit: Int,
        offset: Int,
    ): SchedulerDto.JobExecutionPageDto

    suspend fun listTypes(): List<SchedulerDto.JobTypeInfoDto>
}
