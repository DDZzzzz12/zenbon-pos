package org.example.project.features.employee.database.tables

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.datetime

object WorkSchedules : UUIDTable("work_schedules") {
    val employeeId = reference("employee_id", EmployeeTable)
    val dayOfWeek = integer("day_of_week") // 1=Monday, 7=Sunday
    val startTime = varchar("start_time", 5) // "09:00"
    val endTime = varchar("end_time", 5) // "17:00"
    val breakDuration = integer("break_duration").default(30) // minutes
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
}
