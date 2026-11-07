package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.TimeTrackingApiClient
import org.example.project.core.domain.dto.timetracking.CostCenterDto
import org.example.project.core.domain.dto.timetracking.TimeApprovalDto
import org.example.project.core.domain.dto.timetracking.TimeEntryDto
import org.example.project.core.domain.dto.timetracking.WageTypeDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorTimeTrackingApiClient(
    private val httpCall: HttpCall,
) : TimeTrackingApiClient {
    companion object {
        private const val BASE = "api/plugins/timetracking-plugin"
        private const val APPROVAL = "api/plugins/timetracking-plugin/approval"
        private const val COST_CENTERS = "api/plugins/timetracking-plugin/costcenters"
        private const val WAGE_TYPES = "api/plugins/timetracking-plugin/wagetypes"
        private const val DATEV = "api/plugins/timetracking-plugin/datev"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    // Clock operations
    override suspend fun clockIn(request: TimeEntryDto.ClockInRequestDto): TimeEntryDto.ActiveShiftDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE/clock-in")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun clockOut(request: TimeEntryDto.ClockOutRequestDto): TimeEntryDto.TimeEntryResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE/clock-out")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun startBreak(request: TimeEntryDto.BreakStartRequestDto): TimeEntryDto.ActiveShiftDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE/break/start")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun endBreak(request: TimeEntryDto.BreakEndRequestDto): TimeEntryDto.ActiveShiftDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE/break/end")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    // Shift queries
    override suspend fun getActiveShifts(): List<TimeEntryDto.ActiveShiftDto> = httpCall.httpClient.apiCall { get(url("$BASE/active")) }

    override suspend fun getCurrentShift(): List<TimeEntryDto.TimeEntrySummaryDto> = httpCall.httpClient.apiCall { get(url("$BASE/current")) }

    override suspend fun getMyEntries(
        year: Int,
        month: Int,
    ): List<TimeEntryDto.TimeEntrySummaryDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE/my")) {
                parameter("year", year)
                parameter("month", month)
            }
        }

    override suspend fun getMonthlyOverview(
        year: Int,
        month: Int,
    ): TimeEntryDto.MonthlyOverviewDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE/overview")) {
                parameter("year", year)
                parameter("month", month)
            }
        }

    // PIN-authenticated entries
    override suspend fun getAuthenticatedEntries(request: TimeEntryDto.AuthenticatedEntriesRequestDto): List<TimeEntryDto.TimeEntrySummaryDto> =
        httpCall.httpClient.apiCall {
            post(url("$BASE/my-entries-auth")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    // Manual entry + CRUD
    override suspend fun createManualEntry(request: TimeEntryDto.ManualEntryRequestDto): TimeEntryDto.TimeEntryResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE/manual")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateEntry(
        id: String,
        request: TimeEntryDto.UpdateEntryRequestDto,
    ): TimeEntryDto.TimeEntryResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deleteEntry(id: String) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$BASE/$id")) }
    }

    // Team/admin queries
    override suspend fun getTeamEntries(
        year: Int,
        month: Int,
    ): List<TimeEntryDto.TimeEntrySummaryDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE/team")) {
                parameter("year", year)
                parameter("month", month)
            }
        }

    override suspend fun getAllEntries(
        year: Int,
        month: Int,
    ): List<TimeEntryDto.TimeEntrySummaryDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE/all")) {
                parameter("year", year)
                parameter("month", month)
            }
        }

    // Per-employee queries (manager only)
    override suspend fun getEntriesByEmployeeId(
        employeeId: String,
        year: Int,
        month: Int,
    ): List<TimeEntryDto.TimeEntrySummaryDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE/employee/$employeeId")) {
                parameter("year", year)
                parameter("month", month)
            }
        }

    override suspend fun getMonthlyOverviewByEmployeeId(
        employeeId: String,
        year: Int,
        month: Int,
    ): TimeEntryDto.MonthlyOverviewDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE/employee/$employeeId/overview")) {
                parameter("year", year)
                parameter("month", month)
            }
        }

    // Approval workflow
    override suspend fun submitMonth(request: TimeApprovalDto.SubmitRequestDto): TimeApprovalDto.ApprovalResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$APPROVAL/submit")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun approveMonth(
        id: String,
        request: TimeApprovalDto.ApproveRequestDto,
    ): TimeApprovalDto.ApprovalResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$APPROVAL/$id/approve")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun rejectMonth(
        id: String,
        request: TimeApprovalDto.RejectRequestDto,
    ): TimeApprovalDto.ApprovalResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$APPROVAL/$id/reject")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getPendingApprovals(): List<TimeApprovalDto.PendingApprovalDto> = httpCall.httpClient.apiCall { get(url("$APPROVAL/pending")) }

    override suspend fun getApprovalStatus(
        year: Int,
        month: Int,
    ): TimeApprovalDto.ApprovalResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$APPROVAL/my-status")) {
                parameter("year", year)
                parameter("month", month)
            }
        }

    // Cost centers
    override suspend fun getCostCenters(includeInactive: Boolean): CostCenterDto.CostCenterListResponseDto =
        httpCall.httpClient.apiCall {
            get(url(COST_CENTERS)) { parameter("includeInactive", includeInactive) }
        }

    override suspend fun createCostCenter(request: CostCenterDto.CreateCostCenterRequestDto): CostCenterDto.CostCenterResponseDto =
        httpCall.httpClient.apiCall {
            post(url(COST_CENTERS)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateCostCenter(
        id: String,
        request: CostCenterDto.UpdateCostCenterRequestDto,
    ): CostCenterDto.CostCenterResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$COST_CENTERS/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deleteCostCenter(id: String) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$COST_CENTERS/$id")) }
    }

    // Wage types
    override suspend fun getWageTypes(includeInactive: Boolean): WageTypeDto.WageTypeListResponseDto =
        httpCall.httpClient.apiCall {
            get(url(WAGE_TYPES)) { parameter("includeInactive", includeInactive) }
        }

    override suspend fun createWageType(request: WageTypeDto.CreateWageTypeRequestDto): WageTypeDto.WageTypeResponseDto =
        httpCall.httpClient.apiCall {
            post(url(WAGE_TYPES)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateWageType(
        id: String,
        request: WageTypeDto.UpdateWageTypeRequestDto,
    ): WageTypeDto.WageTypeResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$WAGE_TYPES/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deleteWageType(id: String) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$WAGE_TYPES/$id")) }
    }
}
