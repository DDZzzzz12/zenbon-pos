package org.example.project.features.timetracking.database.tables

import org.example.project.core.domain.enums.timetracking.TimeEntryStatus
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.datetime

object TimeEntryTable : UUIDTable("time_entry") {
    val employeeId = reference("employee_id", EmployeeTable)
    val date = date("date")
    val clockIn = datetime("clock_in")
    val clockOut = datetime("clock_out").nullable()
    val manualEntry = bool("manual_entry").default(false)
    val costCenterId = reference("cost_center_id", CostCenterTable).nullable()
    val comment = text("comment").nullable()
    val status = enumerationByName("status", 20, TimeEntryStatus::class).default(TimeEntryStatus.DRAFT)
    val createdAt = datetime("created_at").clientDefault { currentTimeStamp() }
    val updatedAt = datetime("updated_at").clientDefault { currentTimeStamp() }
}
