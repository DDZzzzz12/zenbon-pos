package org.example.project.core.domain.dto.timetracking

import kotlinx.serialization.Serializable

object TimeApprovalDto {
    @Serializable
    data class ApprovalResponseDto(
        val id: String,
        val employeeId: String,
        val employeeName: String,
        val employeeNumber: Int,
        val periodYear: Int,
        val periodMonth: Int,
        val submittedAt: String,
        val approvedAt: String?,
        val approvedByName: String?,
        val status: String,
        val rejectionReason: String?,
        val totalWorkMinutes: Int,
        val totalOvertimeMinutes: Int,
    )

    @Serializable
    data class SubmitRequestDto(
        val year: Int,
        val month: Int,
    ) {
        fun validate() {
            require(year in 2020..2100) { "Invalid year" }
            require(month in 1..12) { "Invalid month" }
        }
    }

    @Serializable
    data class ApproveRequestDto(
        val approvalId: String,
    )

    @Serializable
    data class RejectRequestDto(
        val approvalId: String,
        val reason: String,
    ) {
        fun validate() {
            require(reason.isNotBlank()) { "Rejection reason is required" }
        }
    }

    @Serializable
    data class PendingApprovalDto(
        val id: String,
        val employeeId: String,
        val employeeName: String,
        val employeeNumber: Int,
        val periodYear: Int,
        val periodMonth: Int,
        val submittedAt: String,
        val totalWorkMinutes: Int,
        val entryCount: Int,
    )
}
