package org.example.project.features.employee.data.repository

import org.example.project.features.employee.database.tables.WorkSchedules
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.employee.domain.models.WorkSchedule
import org.example.project.features.employee.domain.repository.WorkScheduleRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import java.util.UUID

class WorkScheduleRepositoryImpl : WorkScheduleRepository {
    override fun findByEmployeeId(employeeId: UUID): List<WorkSchedule> =
        WorkSchedule
            .find { WorkSchedules.employeeId eq employeeId }
            .sortedWith(compareBy({ it.dayOfWeek }, { it.startTime }))
            .toList()

    override fun findByEmployeeIdAndDay(
        employeeId: UUID,
        dayOfWeek: Int,
    ): List<WorkSchedule> =
        WorkSchedule
            .find {
                (WorkSchedules.employeeId eq employeeId) and (WorkSchedules.dayOfWeek eq dayOfWeek)
            }.sortedBy { it.startTime }
            .toList()

    override fun create(
        employeeId: UUID,
        dayOfWeek: Int,
        startTime: String,
        endTime: String,
        breakDuration: Int,
    ): WorkSchedule {
        val now = currentTimeStamp()
        val employee =
            Employee.findById(employeeId)
                ?: throw NoSuchElementException("Employee not found: $employeeId")
        return WorkSchedule.new {
            this.employee = employee
            this.dayOfWeek = dayOfWeek
            this.startTime = startTime
            this.endTime = endTime
            this.breakDuration = breakDuration
            this.createdAt = now
            this.updatedAt = now
        }
    }

    override fun deleteByEmployeeIdAndDay(
        employeeId: UUID,
        dayOfWeek: Int,
    ) {
        WorkSchedule
            .find {
                (WorkSchedules.employeeId eq employeeId) and (WorkSchedules.dayOfWeek eq dayOfWeek)
            }.forEach { it.delete() }
    }

    override fun deleteAllByEmployeeId(employeeId: UUID) {
        WorkSchedule
            .find { WorkSchedules.employeeId eq employeeId }
            .forEach { it.delete() }
    }
}
