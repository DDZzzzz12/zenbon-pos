package org.example.project.features.employee.domain.service

import org.example.project.core.domain.dto.employee.WorkScheduleDto
import java.util.UUID

/**
 * Migrated to throws-style in Stage B Batch 10.
 */
interface WorkScheduleService {
    suspend fun getWeeklySchedule(employeeId: UUID): WorkScheduleDto.WeeklyScheduleResponseDto

    suspend fun updateDaySchedule(
        employeeId: UUID,
        request: WorkScheduleDto.UpdateDayRequestDto,
    ): WorkScheduleDto.WeeklyScheduleResponseDto

    suspend fun updateWeekSchedule(
        employeeId: UUID,
        request: WorkScheduleDto.UpdateWeekRequestDto,
    ): WorkScheduleDto.WeeklyScheduleResponseDto
}
