package org.example.project.features.scheduler.presentation.state

import org.example.project.core.domain.dto.scheduler.SchedulerDto

data class ScheduledJobsState(
    val jobs: List<SchedulerDto.ScheduledJobResponseDto> = emptyList(),
    val types: List<SchedulerDto.JobTypeInfoDto> = emptyList(),
    val selectedJobDetail: SchedulerDto.ScheduledJobDetailDto? = null,
    val isLoading: Boolean = false,
)
