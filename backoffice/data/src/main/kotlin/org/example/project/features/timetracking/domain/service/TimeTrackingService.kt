package org.example.project.features.timetracking.domain.service

import org.example.project.core.domain.dto.timetracking.TimeEntryDto

/**
 * Service interface for TimeTracking business operations.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * StatusPages catches it and emits application/problem+json.
 */
interface TimeTrackingService {
    suspend fun clockIn(request: TimeEntryDto.ClockInRequestDto): TimeEntryDto.ActiveShiftDto

    suspend fun clockOut(request: TimeEntryDto.ClockOutRequestDto): TimeEntryDto.TimeEntryResponseDto

    suspend fun startBreak(request: TimeEntryDto.BreakStartRequestDto): TimeEntryDto.ActiveShiftDto

    suspend fun endBreak(request: TimeEntryDto.BreakEndRequestDto): TimeEntryDto.ActiveShiftDto

    suspend fun getActiveShifts(
        employeeId: String,
        /** Whether this caller holds TIMETRACKING_ALL_READ — everyone's entries, not just their own. */
        seesEveryone: Boolean,
    ): List<TimeEntryDto.ActiveShiftDto>

    suspend fun getMyEntries(
        employeeId: String,
        year: Int,
        month: Int,
    ): List<TimeEntryDto.TimeEntrySummaryDto>

    suspend fun getMonthlyOverview(
        employeeId: String,
        year: Int,
        month: Int,
    ): TimeEntryDto.MonthlyOverviewDto

    suspend fun createManualEntry(
        request: TimeEntryDto.ManualEntryRequestDto,
        createdByEmployeeId: String,
    ): TimeEntryDto.TimeEntryResponseDto

    suspend fun updateEntry(
        entryId: String,
        request: TimeEntryDto.UpdateEntryRequestDto,
    ): TimeEntryDto.TimeEntryResponseDto

    suspend fun deleteEntry(entryId: String)

    suspend fun getTeamEntries(
        year: Int,
        month: Int,
    ): List<TimeEntryDto.TimeEntrySummaryDto>

    suspend fun getAllEntries(
        year: Int,
        month: Int,
    ): List<TimeEntryDto.TimeEntrySummaryDto>

    suspend fun getAuthenticatedEntries(request: TimeEntryDto.AuthenticatedEntriesRequestDto): List<TimeEntryDto.TimeEntrySummaryDto>
}
