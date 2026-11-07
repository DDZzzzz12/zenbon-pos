package org.example.project.core.domain.dto.timetracking

import kotlinx.serialization.Serializable

object TimeEntryDto {
    @Serializable
    data class TimeEntryResponseDto(
        val id: String,
        val employeeId: String,
        val employeeName: String,
        val employeeNumber: String,
        val date: String,
        val clockIn: String,
        val clockOut: String?,
        val breaks: List<BreakResponseDto>,
        val totalBreakMinutes: Int,
        val netWorkMinutes: Int,
        val manualEntry: Boolean,
        val costCenterId: String?,
        val costCenterCode: String?,
        val costCenterName: String?,
        val comment: String?,
        val status: String,
        val createdAt: String,
        val updatedAt: String,
    )

    @Serializable
    data class TimeEntrySummaryDto(
        val id: String,
        val employeeId: String? = null,
        val employeeName: String? = null,
        val date: String,
        val clockIn: String,
        val clockOut: String?,
        val totalBreakMinutes: Int,
        val netWorkMinutes: Int,
        val costCenterName: String?,
        val status: String,
        val manualEntry: Boolean,
    )

    @Serializable
    data class ActiveShiftDto(
        val id: String,
        val employeeId: String,
        val employeeName: String,
        val employeeNumber: String,
        val clockIn: String,
        val elapsedMinutes: Int,
        val isOnBreak: Boolean,
        val currentBreakStartedAt: String?,
        val totalBreakMinutes: Int,
        val costCenterName: String?,
    )

    @Serializable
    data class MonthlyOverviewDto(
        val employeeId: String,
        val employeeName: String,
        val year: Int,
        val month: Int,
        val contractedWeeklyHours: Double?,
        val targetMonthlyMinutes: Int?,
        val actualWorkMinutes: Int,
        val overtimeMinutes: Int,
        val totalBreakMinutes: Int,
        val workingDays: Int,
        val status: String,
    )

    @Serializable
    data class BreakResponseDto(
        val id: String,
        val breakStart: String,
        val breakEnd: String?,
        val durationMinutes: Int?,
    )

    @Serializable
    data class ClockInRequestDto(
        val employeeNumber: String,
        val pin: String,
        val costCenterId: String? = null,
        val comment: String? = null,
    )

    @Serializable
    data class ClockOutRequestDto(
        val employeeNumber: String,
        val pin: String,
        val comment: String? = null,
    )

    @Serializable
    data class BreakStartRequestDto(
        val employeeNumber: String,
        val pin: String,
    )

    @Serializable
    data class BreakEndRequestDto(
        val employeeNumber: String,
        val pin: String,
    )

    @Serializable
    data class ManualEntryRequestDto(
        val employeeId: String,
        val date: String,
        val startTime: String,
        val endTime: String,
        val breakMinutes: Int = 0,
        val costCenterId: String? = null,
        val comment: String? = null,
    ) {
        fun validate() {
            require(date.isNotBlank()) { "Date is required" }
            require(startTime.isNotBlank()) { "Start time is required" }
            require(endTime.isNotBlank()) { "End time is required" }
        }
    }

    @Serializable
    data class UpdateEntryRequestDto(
        val startTime: String? = null,
        val endTime: String? = null,
        val costCenterId: String? = null,
        val comment: String? = null,
    )

    @Serializable
    data class AuthenticatedEntriesRequestDto(
        val employeeNumber: String,
        val pin: String,
        val year: Int,
        val month: Int,
    )
}
