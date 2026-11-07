package org.example.project.features.scheduler.data.remote

import org.example.project.core.domain.dto.scheduler.SchedulerDto

/**
 * API client for scheduled-jobs endpoints.
 *
 * Throws [org.example.project.core.network.error.PosApiException] for non-2xx responses
 * and [org.example.project.core.network.error.NetworkApiException] for transport failures.
 */
interface ScheduledJobApiClient {
    suspend fun list(): List<SchedulerDto.ScheduledJobResponseDto>

    suspend fun getDetail(id: String): SchedulerDto.ScheduledJobDetailDto

    suspend fun runNow(id: String): SchedulerDto.JobExecutionResponseDto

    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ): SchedulerDto.ScheduledJobResponseDto

    suspend fun listExecutions(
        id: String,
        limit: Int,
        offset: Int,
    ): SchedulerDto.JobExecutionPageDto

    suspend fun listTypes(): List<SchedulerDto.JobTypeInfoDto>
}
