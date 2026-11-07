package org.example.project.features.timetracking.data.repository

import org.example.project.core.domain.enums.timetracking.ApprovalStatus
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.timetracking.database.tables.TimeApprovalTable
import org.example.project.features.timetracking.domain.models.TimeApproval
import org.example.project.features.timetracking.domain.repository.TimeApprovalRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import java.util.UUID

class TimeApprovalRepositoryImpl : TimeApprovalRepository {
    override fun findById(id: UUID): TimeApproval? = TimeApproval.findById(id)

    override fun findByEmployeeAndPeriod(
        employeeId: UUID,
        year: Int,
        month: Int,
    ): TimeApproval? =
        TimeApproval
            .find {
                (TimeApprovalTable.employeeId eq employeeId) and
                    (TimeApprovalTable.periodYear eq year) and
                    (TimeApprovalTable.periodMonth eq month)
            }.firstOrNull()

    override fun findPendingApprovals(): List<TimeApproval> =
        TimeApproval
            .find {
                TimeApprovalTable.status eq ApprovalStatus.SUBMITTED
            }.toList()

    override fun create(
        employeeId: UUID,
        year: Int,
        month: Int,
    ): TimeApproval =
        TimeApproval.new {
            this.employee = Employee[employeeId]
            this.periodYear = year
            this.periodMonth = month
            this.submittedAt = currentTimeStamp()
            this.status = ApprovalStatus.SUBMITTED
        }

    override fun updateStatus(
        id: UUID,
        status: ApprovalStatus,
        approvedBy: UUID?,
        rejectionReason: String?,
    ): TimeApproval? {
        val approval = TimeApproval.findById(id) ?: return null
        approval.status = status
        if (status == ApprovalStatus.APPROVED && approvedBy != null) {
            approval.approver = Employee[approvedBy]
            approval.approvedAt = currentTimeStamp()
        }
        rejectionReason?.let { approval.rejectionReason = it }
        return approval
    }
}
