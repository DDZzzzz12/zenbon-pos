package org.example.project.admin.features.staff.time

import org.example.project.core.data.remote.TimeTrackingApiClient
import org.example.project.core.domain.dto.timetracking.*
import org.example.project.core.network.error.NetworkApiException

fun summary(
    id: String,
    date: String,
) = TimeEntryDto.TimeEntrySummaryDto(
    id = id,
    employeeId = "7",
    employeeName = "Dan",
    date = date,
    clockIn = "09:00",
    clockOut = "17:00",
    totalBreakMinutes = 30,
    netWorkMinutes = 450,
    costCenterName = null,
    status = "OPEN",
    manualEntry = false,
)

private fun overview(
    year: Int,
    month: Int,
) = TimeEntryDto.MonthlyOverviewDto(
    employeeId = "7",
    employeeName = "Dan",
    year = year,
    month = month,
    contractedWeeklyHours = 40.0,
    targetMonthlyMinutes = 9600,
    actualWorkMinutes = 450,
    overtimeMinutes = 0,
    totalBreakMinutes = 30,
    workingDays = 1,
    status = "OPEN",
)

private fun response(id: String) =
    TimeEntryDto.TimeEntryResponseDto(
        id = id,
        employeeId = "7",
        employeeName = "Dan",
        employeeNumber = "E7",
        date = "2026-07-10",
        clockIn = "09:00",
        clockOut = "17:00",
        breaks = emptyList(),
        totalBreakMinutes = 30,
        netWorkMinutes = 450,
        manualEntry = true,
        costCenterId = null,
        costCenterCode = null,
        costCenterName = null,
        comment = null,
        status = "OPEN",
        createdAt = "2026-07-10T00:00",
        updatedAt = "2026-07-10T00:00",
    )

fun pendingApproval(
    id: String,
    employeeId: String,
) = TimeApprovalDto.PendingApprovalDto(
    id = id,
    employeeId = employeeId,
    employeeName = "Dan",
    employeeNumber = 7,
    periodYear = 2026,
    periodMonth = 7,
    submittedAt = "2026-08-01T09:00",
    totalWorkMinutes = 9600,
    entryCount = 21,
)

private fun approvalResponse(id: String) =
    TimeApprovalDto.ApprovalResponseDto(
        id = id,
        employeeId = "7",
        employeeName = "Dan",
        employeeNumber = 7,
        periodYear = 2026,
        periodMonth = 7,
        submittedAt = "2026-08-01T09:00",
        approvedAt = null,
        approvedByName = null,
        status = "PENDING",
        rejectionReason = null,
        totalWorkMinutes = 9600,
        totalOvertimeMinutes = 0,
    )

class FakeTimeTrackingApiClient(
    private val entries: List<TimeEntryDto.TimeEntrySummaryDto> = emptyList(),
    private val pending: List<TimeApprovalDto.PendingApprovalDto> = emptyList(),
) : TimeTrackingApiClient {
    var queriedEmployeeId: String? = null
    var createdRequest: TimeEntryDto.ManualEntryRequestDto? = null
    var updatedId: String? = null
    var updatedRequest: TimeEntryDto.UpdateEntryRequestDto? = null
    var deletedId: String? = null
    var approvedId: String? = null
    var rejectedId: String? = null
    var rejectReason: String? = null
    var costCenters: List<CostCenterDto.CostCenterResponseDto> = emptyList()
    var costCentersShouldFail: Boolean = false

    override suspend fun getEntriesByEmployeeId(
        employeeId: String,
        year: Int,
        month: Int,
    ): List<TimeEntryDto.TimeEntrySummaryDto> {
        queriedEmployeeId =
            employeeId
        return entries
    }

    override suspend fun getMonthlyOverviewByEmployeeId(
        employeeId: String,
        year: Int,
        month: Int,
    ) = overview(year, month)

    override suspend fun createManualEntry(request: TimeEntryDto.ManualEntryRequestDto): TimeEntryDto.TimeEntryResponseDto {
        createdRequest =
            request
        return response("new")
    }

    override suspend fun updateEntry(
        id: String,
        request: TimeEntryDto.UpdateEntryRequestDto,
    ): TimeEntryDto.TimeEntryResponseDto {
        updatedId =
            id
        updatedRequest = request
        return response(id)
    }

    override suspend fun deleteEntry(id: String) {
        deletedId = id
    }

    // --- unused-in-phase members: stub every other TimeTrackingApiClient method ---
    override suspend fun clockIn(request: TimeEntryDto.ClockInRequestDto): TimeEntryDto.ActiveShiftDto = error("unused")

    override suspend fun clockOut(request: TimeEntryDto.ClockOutRequestDto): TimeEntryDto.TimeEntryResponseDto = error("unused")

    override suspend fun startBreak(request: TimeEntryDto.BreakStartRequestDto): TimeEntryDto.ActiveShiftDto = error("unused")

    override suspend fun endBreak(request: TimeEntryDto.BreakEndRequestDto): TimeEntryDto.ActiveShiftDto = error("unused")

    override suspend fun getActiveShifts(): List<TimeEntryDto.ActiveShiftDto> = error("unused")

    override suspend fun getCurrentShift(): List<TimeEntryDto.TimeEntrySummaryDto> = error("unused")

    override suspend fun getMyEntries(
        year: Int,
        month: Int,
    ): List<TimeEntryDto.TimeEntrySummaryDto> = error("unused")

    override suspend fun getMonthlyOverview(
        year: Int,
        month: Int,
    ): TimeEntryDto.MonthlyOverviewDto = error("unused")

    override suspend fun getAuthenticatedEntries(request: TimeEntryDto.AuthenticatedEntriesRequestDto): List<TimeEntryDto.TimeEntrySummaryDto> = error("unused")

    override suspend fun getTeamEntries(
        year: Int,
        month: Int,
    ): List<TimeEntryDto.TimeEntrySummaryDto> = error("unused")

    override suspend fun getAllEntries(
        year: Int,
        month: Int,
    ): List<TimeEntryDto.TimeEntrySummaryDto> = error("unused")

    override suspend fun submitMonth(request: TimeApprovalDto.SubmitRequestDto): TimeApprovalDto.ApprovalResponseDto = error("unused")

    override suspend fun approveMonth(
        id: String,
        request: TimeApprovalDto.ApproveRequestDto,
    ): TimeApprovalDto.ApprovalResponseDto {
        approvedId = id
        return approvalResponse(id)
    }

    override suspend fun rejectMonth(
        id: String,
        request: TimeApprovalDto.RejectRequestDto,
    ): TimeApprovalDto.ApprovalResponseDto {
        rejectedId = id
        rejectReason = request.reason
        return approvalResponse(id)
    }

    override suspend fun getPendingApprovals(): List<TimeApprovalDto.PendingApprovalDto> = pending

    override suspend fun getApprovalStatus(
        year: Int,
        month: Int,
    ): TimeApprovalDto.ApprovalResponseDto = error("unused")

    override suspend fun getCostCenters(includeInactive: Boolean): CostCenterDto.CostCenterListResponseDto {
        if (costCentersShouldFail) throw NetworkApiException(RuntimeException("cost centres disabled"))
        return CostCenterDto.CostCenterListResponseDto(costCenters = costCenters, total = costCenters.size)
    }

    override suspend fun createCostCenter(request: CostCenterDto.CreateCostCenterRequestDto): CostCenterDto.CostCenterResponseDto = error("unused")

    override suspend fun updateCostCenter(
        id: String,
        request: CostCenterDto.UpdateCostCenterRequestDto,
    ): CostCenterDto.CostCenterResponseDto = error("unused")

    override suspend fun deleteCostCenter(id: String) {}

    override suspend fun getWageTypes(includeInactive: Boolean): WageTypeDto.WageTypeListResponseDto = error("unused")

    override suspend fun createWageType(request: WageTypeDto.CreateWageTypeRequestDto): WageTypeDto.WageTypeResponseDto = error("unused")

    override suspend fun updateWageType(
        id: String,
        request: WageTypeDto.UpdateWageTypeRequestDto,
    ): WageTypeDto.WageTypeResponseDto = error("unused")

    override suspend fun deleteWageType(id: String) {}
}
