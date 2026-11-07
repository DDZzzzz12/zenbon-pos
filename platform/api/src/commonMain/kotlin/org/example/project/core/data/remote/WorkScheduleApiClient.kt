package org.example.project.core.data.remote

import org.example.project.core.domain.dto.employee.WorkScheduleDto

/**
 * API client for employee weekly work-schedule endpoints.
 *
 * Throws [org.example.project.core.network.error.PosApiException] for non-2xx responses
 * and [org.example.project.core.network.error.NetworkApiException] for transport failures.
 */
interface WorkScheduleApiClient {
    suspend fun getWeeklySchedule(employeeId: String): WorkScheduleDto.WeeklyScheduleResponseDto

    suspend fun updateDaySchedule(
        employeeId: String,
        request: WorkScheduleDto.UpdateDayRequestDto,
    ): WorkScheduleDto.WeeklyScheduleResponseDto

    suspend fun updateWeekSchedule(
        employeeId: String,
        request: WorkScheduleDto.UpdateWeekRequestDto,
    ): WorkScheduleDto.WeeklyScheduleResponseDto
}
