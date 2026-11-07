package org.example.project.features.timetracking.mapper

import org.example.project.core.domain.dto.timetracking.TimeApprovalDto
import org.example.project.features.timetracking.domain.models.TimeApproval

fun TimeApproval.toResponse(
    totalWorkMinutes: Int = 0,
    totalOvertimeMinutes: Int = 0,
): TimeApprovalDto.ApprovalResponseDto =
    TimeApprovalDto.ApprovalResponseDto(
        id = id.value.toString(),
        employeeId = employee.id.value.toString(),
        employeeName = employee.fullName,
        employeeNumber = employee.employeeNumber.toIntOrNull() ?: 0,
        periodYear = periodYear,
        periodMonth = periodMonth,
        submittedAt = submittedAt.toString(),
        approvedAt = approvedAt?.toString(),
        approvedByName = approver?.fullName,
        status = status.name,
        rejectionReason = rejectionReason,
        totalWorkMinutes = totalWorkMinutes,
        totalOvertimeMinutes = totalOvertimeMinutes,
    )

fun TimeApproval.toPendingApproval(
    totalWorkMinutes: Int,
    entryCount: Int,
): TimeApprovalDto.PendingApprovalDto =
    TimeApprovalDto.PendingApprovalDto(
        id = id.value.toString(),
        employeeId = employee.id.value.toString(),
        employeeName = employee.fullName,
        employeeNumber = employee.employeeNumber.toIntOrNull() ?: 0,
        periodYear = periodYear,
        periodMonth = periodMonth,
        submittedAt = submittedAt.toString(),
        totalWorkMinutes = totalWorkMinutes,
        entryCount = entryCount,
    )
