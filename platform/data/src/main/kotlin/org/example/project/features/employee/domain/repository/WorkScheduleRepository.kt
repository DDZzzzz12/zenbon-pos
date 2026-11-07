package org.example.project.features.employee.domain.repository

import org.example.project.features.employee.domain.models.WorkSchedule
import java.util.UUID

interface WorkScheduleRepository {
    fun findByEmployeeId(employeeId: UUID): List<WorkSchedule>

    fun findByEmployeeIdAndDay(
        employeeId: UUID,
        dayOfWeek: Int,
    ): List<WorkSchedule>

    fun create(
        employeeId: UUID,
        dayOfWeek: Int,
        startTime: String,
        endTime: String,
        breakDuration: Int,
    ): WorkSchedule

    fun deleteByEmployeeIdAndDay(
        employeeId: UUID,
        dayOfWeek: Int,
    )

    fun deleteAllByEmployeeId(employeeId: UUID)
}
