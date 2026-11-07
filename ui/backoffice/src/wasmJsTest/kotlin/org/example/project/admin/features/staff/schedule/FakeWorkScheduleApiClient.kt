package org.example.project.admin.features.staff.schedule

import org.example.project.core.data.remote.WorkScheduleApiClient
import org.example.project.core.domain.dto.employee.WorkScheduleDto

class FakeWorkScheduleApiClient(
    private var weekly: WorkScheduleDto.WeeklyScheduleResponseDto,
) : WorkScheduleApiClient {
    var updatedEmployeeId: String? = null
    var updatedRequest: WorkScheduleDto.UpdateDayRequestDto? = null

    override suspend fun getWeeklySchedule(employeeId: String) = weekly

    override suspend fun updateDaySchedule(
        employeeId: String,
        request: WorkScheduleDto.UpdateDayRequestDto,
    ): WorkScheduleDto.WeeklyScheduleResponseDto {
        updatedEmployeeId = employeeId
        updatedRequest = request
        weekly =
            weekly.copy(
                days =
                    weekly.days.filter { it.dayOfWeek != request.dayOfWeek } +
                        WorkScheduleDto.DayScheduleDto(request.dayOfWeek, request.shifts),
            )
        return weekly
    }

    override suspend fun updateWeekSchedule(
        employeeId: String,
        request: WorkScheduleDto.UpdateWeekRequestDto,
    ) = weekly
}
