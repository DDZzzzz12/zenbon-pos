package org.example.project.features.timetracking.domain.repository

import org.example.project.core.domain.enums.timetracking.ApprovalStatus
import org.example.project.features.timetracking.domain.models.TimeApproval
import java.util.UUID

interface TimeApprovalRepository {
    fun findById(id: UUID): TimeApproval?

    fun findByEmployeeAndPeriod(
        employeeId: UUID,
        year: Int,
        month: Int,
    ): TimeApproval?

    fun findPendingApprovals(): List<TimeApproval>

    fun create(
        employeeId: UUID,
        year: Int,
        month: Int,
    ): TimeApproval

    fun updateStatus(
        id: UUID,
        status: ApprovalStatus,
        approvedBy: UUID?,
        rejectionReason: String?,
    ): TimeApproval?
}
