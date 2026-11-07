package org.example.project.core.data.remote

import org.example.project.core.domain.dto.timetracking.CostCenterDto
import org.example.project.core.domain.dto.timetracking.TimeApprovalDto
import org.example.project.core.domain.dto.timetracking.TimeEntryDto
import org.example.project.core.domain.dto.timetracking.WageTypeDto

/**
 * Time-tracking API client.
 *
 * Implementations throw [org.example.project.core.network.error.PosApiException] on
 * non-2xx responses (decoded RFC 7807 ProblemDetail) and
 * [org.example.project.core.network.error.NetworkApiException] on transport failures.
 * VM call sites wrap calls in `runCatchingApi { ... }.onError { _error.value = it }`.
 */
interface TimeTrackingApiClient {
    // Clock operations
    suspend fun clockIn(request: TimeEntryDto.ClockInRequestDto): TimeEntryDto.ActiveShiftDto

    suspend fun clockOut(request: TimeEntryDto.ClockOutRequestDto): TimeEntryDto.TimeEntryResponseDto

    suspend fun startBreak(request: TimeEntryDto.BreakStartRequestDto): TimeEntryDto.ActiveShiftDto

    suspend fun endBreak(request: TimeEntryDto.BreakEndRequestDto): TimeEntryDto.ActiveShiftDto

    // Shift queries
    suspend fun getActiveShifts(): List<TimeEntryDto.ActiveShiftDto>

    suspend fun getCurrentShift(): List<TimeEntryDto.TimeEntrySummaryDto>

    suspend fun getMyEntries(
        year: Int,
        month: Int,
    ): List<TimeEntryDto.TimeEntrySummaryDto>

    suspend fun getMonthlyOverview(
        year: Int,
        month: Int,
    ): TimeEntryDto.MonthlyOverviewDto

    // PIN-authenticated entries (for basic terminal)
    suspend fun getAuthenticatedEntries(request: TimeEntryDto.AuthenticatedEntriesRequestDto): List<TimeEntryDto.TimeEntrySummaryDto>

    // Manual entry + CRUD
    suspend fun createManualEntry(request: TimeEntryDto.ManualEntryRequestDto): TimeEntryDto.TimeEntryResponseDto

    suspend fun updateEntry(
        id: String,
        request: TimeEntryDto.UpdateEntryRequestDto,
    ): TimeEntryDto.TimeEntryResponseDto

    suspend fun deleteEntry(id: String)

    // Team/admin queries
    suspend fun getTeamEntries(
        year: Int,
        month: Int,
    ): List<TimeEntryDto.TimeEntrySummaryDto>

    suspend fun getAllEntries(
        year: Int,
        month: Int,
    ): List<TimeEntryDto.TimeEntrySummaryDto>

    // Per-employee queries (manager only)
    suspend fun getEntriesByEmployeeId(
        employeeId: String,
        year: Int,
        month: Int,
    ): List<TimeEntryDto.TimeEntrySummaryDto>

    suspend fun getMonthlyOverviewByEmployeeId(
        employeeId: String,
        year: Int,
        month: Int,
    ): TimeEntryDto.MonthlyOverviewDto

    // Approval workflow
    suspend fun submitMonth(request: TimeApprovalDto.SubmitRequestDto): TimeApprovalDto.ApprovalResponseDto

    suspend fun approveMonth(
        id: String,
        request: TimeApprovalDto.ApproveRequestDto,
    ): TimeApprovalDto.ApprovalResponseDto

    suspend fun rejectMonth(
        id: String,
        request: TimeApprovalDto.RejectRequestDto,
    ): TimeApprovalDto.ApprovalResponseDto

    suspend fun getPendingApprovals(): List<TimeApprovalDto.PendingApprovalDto>

    suspend fun getApprovalStatus(
        year: Int,
        month: Int,
    ): TimeApprovalDto.ApprovalResponseDto

    // Cost centers
    suspend fun getCostCenters(includeInactive: Boolean = false): CostCenterDto.CostCenterListResponseDto

    suspend fun createCostCenter(request: CostCenterDto.CreateCostCenterRequestDto): CostCenterDto.CostCenterResponseDto

    suspend fun updateCostCenter(
        id: String,
        request: CostCenterDto.UpdateCostCenterRequestDto,
    ): CostCenterDto.CostCenterResponseDto

    suspend fun deleteCostCenter(id: String)

    // Wage types
    suspend fun getWageTypes(includeInactive: Boolean = false): WageTypeDto.WageTypeListResponseDto

    suspend fun createWageType(request: WageTypeDto.CreateWageTypeRequestDto): WageTypeDto.WageTypeResponseDto

    suspend fun updateWageType(
        id: String,
        request: WageTypeDto.UpdateWageTypeRequestDto,
    ): WageTypeDto.WageTypeResponseDto

    suspend fun deleteWageType(id: String)
}
