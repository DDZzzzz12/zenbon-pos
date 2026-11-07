package org.example.project.features.timetracking.database.tables

import org.example.project.core.domain.enums.timetracking.ApprovalStatus
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.datetime

object TimeApprovalTable : UUIDTable("time_approval") {
    val employeeId = reference("employee_id", EmployeeTable)
    val approvedBy = reference("approved_by", EmployeeTable).nullable()
    val periodYear = integer("period_year")
    val periodMonth = integer("period_month")
    val submittedAt = datetime("submitted_at").clientDefault { currentTimeStamp() }
    val approvedAt = datetime("approved_at").nullable()
    val status = enumerationByName("status", 20, ApprovalStatus::class).default(ApprovalStatus.SUBMITTED)
    val rejectionReason = text("rejection_reason").nullable()

    init {
        uniqueIndex("uq_approval_employee_period", employeeId, periodYear, periodMonth)
    }
}
