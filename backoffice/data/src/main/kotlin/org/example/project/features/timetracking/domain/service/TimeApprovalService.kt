package org.example.project.features.timetracking.domain.service

import org.example.project.core.domain.dto.timetracking.TimeApprovalDto

/**
 * Service interface for TimeApproval business operations.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * StatusPages catches it and emits application/problem+json.
 */
interface TimeApprovalService {
    suspend fun submitMonth(
        employeeId: String,
        request: TimeApprovalDto.SubmitRequestDto,
    ): TimeApprovalDto.ApprovalResponseDto

    suspend fun approveMonth(
        managerId: String,
        request: TimeApprovalDto.ApproveRequestDto,
    ): TimeApprovalDto.ApprovalResponseDto

    suspend fun rejectMonth(
        managerId: String,
        request: TimeApprovalDto.RejectRequestDto,
    ): TimeApprovalDto.ApprovalResponseDto

    suspend fun getPendingApprovals(): List<TimeApprovalDto.PendingApprovalDto>

    suspend fun getApprovalStatus(
        employeeId: String,
        year: Int,
        month: Int,
    ): TimeApprovalDto.ApprovalResponseDto
}
