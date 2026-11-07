package org.example.project.features.timetracking.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.timetracking.TimeApprovalDto
import org.example.project.core.domain.enums.timetracking.ApprovalStatus
import org.example.project.core.domain.enums.timetracking.TimeEntryStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.timetracking.domain.repository.TimeApprovalRepository
import org.example.project.features.timetracking.domain.repository.TimeEntryRepository
import org.example.project.features.timetracking.domain.service.TimeApprovalService
import org.example.project.features.timetracking.mapper.toPendingApproval
import org.example.project.features.timetracking.mapper.toResponse
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.slf4j.LoggerFactory
import java.util.UUID

class TimeApprovalServiceImpl(
    private val timeApprovalRepository: TimeApprovalRepository,
    private val timeEntryRepository: TimeEntryRepository,
) : TimeApprovalService {
    private val logger = LoggerFactory.getLogger(TimeApprovalServiceImpl::class.java)

    override suspend fun submitMonth(
        employeeId: String,
        request: TimeApprovalDto.SubmitRequestDto,
    ): TimeApprovalDto.ApprovalResponseDto {
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid request")
        }
        val empId = UUID.fromString(employeeId)

        val existing = dbQuery { timeApprovalRepository.findByEmployeeAndPeriod(empId, request.year, request.month) }
        if (existing != null) {
            throw PosException(
                PosErrorCode.INVALID_REQUEST,
                HttpStatusCode.Conflict,
                "Month already submitted",
            )
        }

        val entries = dbQuery { timeEntryRepository.findByEmployeeAndMonth(empId, request.year, request.month) }
        if (entries.isEmpty()) {
            throw PosException(
                PosErrorCode.RESOURCE_NOT_FOUND,
                HttpStatusCode.NotFound,
                "No time entries found for this period",
            )
        }

        return dbQuery {
            timeEntryRepository.updateStatusByEmployeeAndMonth(empId, request.year, request.month, TimeEntryStatus.SUBMITTED)
            val approval = timeApprovalRepository.create(empId, request.year, request.month)
            val totalWork = calculateTotalWorkMinutes(entries)
            approval.toResponse(totalWorkMinutes = totalWork)
        }
    }

    override suspend fun approveMonth(
        managerId: String,
        request: TimeApprovalDto.ApproveRequestDto,
    ): TimeApprovalDto.ApprovalResponseDto =
        dbQuery {
            val approvalId = UUID.fromString(request.approvalId)
            val approval =
                timeApprovalRepository.findById(approvalId)
                    ?: throw PosException(
                        PosErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "Approval not found",
                    )

            if (approval.status != ApprovalStatus.SUBMITTED) {
                throw PosException(
                    PosErrorCode.INVALID_REQUEST,
                    HttpStatusCode.Conflict,
                    "Can only approve SUBMITTED months",
                )
            }

            timeEntryRepository.updateStatusByEmployeeAndMonth(
                approval.employee.id.value,
                approval.periodYear,
                approval.periodMonth,
                TimeEntryStatus.APPROVED,
            )

            val updated =
                timeApprovalRepository.updateStatus(
                    approvalId,
                    ApprovalStatus.APPROVED,
                    UUID.fromString(managerId),
                    null,
                ) ?: throw PosException(
                    PosErrorCode.INTERNAL_ERROR,
                    HttpStatusCode.InternalServerError,
                    "Update failed",
                )

            updated.toResponse()
        }

    override suspend fun rejectMonth(
        managerId: String,
        request: TimeApprovalDto.RejectRequestDto,
    ): TimeApprovalDto.ApprovalResponseDto {
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid request")
        }
        return dbQuery {
            val approvalId = UUID.fromString(request.approvalId)
            val approval =
                timeApprovalRepository.findById(approvalId)
                    ?: throw PosException(
                        PosErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "Approval not found",
                    )

            if (approval.status != ApprovalStatus.SUBMITTED) {
                throw PosException(
                    PosErrorCode.INVALID_REQUEST,
                    HttpStatusCode.Conflict,
                    "Can only reject SUBMITTED months",
                )
            }

            timeEntryRepository.updateStatusByEmployeeAndMonth(
                approval.employee.id.value,
                approval.periodYear,
                approval.periodMonth,
                TimeEntryStatus.DRAFT,
            )

            val updated =
                timeApprovalRepository.updateStatus(
                    approvalId,
                    ApprovalStatus.REJECTED,
                    UUID.fromString(managerId),
                    request.reason,
                ) ?: throw PosException(
                    PosErrorCode.INTERNAL_ERROR,
                    HttpStatusCode.InternalServerError,
                    "Update failed",
                )

            updated.toResponse()
        }
    }

    override suspend fun getPendingApprovals(): List<TimeApprovalDto.PendingApprovalDto> =
        dbQuery {
            val pending = timeApprovalRepository.findPendingApprovals()
            pending.map { approval ->
                val entries =
                    timeEntryRepository.findByEmployeeAndMonth(
                        approval.employee.id.value,
                        approval.periodYear,
                        approval.periodMonth,
                    )
                val totalWork = calculateTotalWorkMinutes(entries)
                approval.toPendingApproval(totalWork, entries.size)
            }
        }

    override suspend fun getApprovalStatus(
        employeeId: String,
        year: Int,
        month: Int,
    ): TimeApprovalDto.ApprovalResponseDto =
        dbQuery {
            val approval =
                timeApprovalRepository.findByEmployeeAndPeriod(UUID.fromString(employeeId), year, month)
                    ?: throw PosException(
                        PosErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "No approval found for this period",
                    )
            approval.toResponse()
        }

    private fun calculateTotalWorkMinutes(entries: List<org.example.project.features.timetracking.domain.models.TimeEntry>): Int =
        entries.sumOf { entry ->
            val clockOut = entry.clockOut ?: currentTimeStamp()
            val grossMinutes = minutesBetween(entry.clockIn, clockOut)
            val breakMinutes =
                entry.breaks.sumOf { b ->
                    val bEnd = b.breakEnd ?: currentTimeStamp()
                    minutesBetween(b.breakStart, bEnd)
                }
            grossMinutes - breakMinutes
        }

    private fun minutesBetween(
        start: kotlinx.datetime.LocalDateTime,
        end: kotlinx.datetime.LocalDateTime,
    ): Int {
        val startEpoch = start.date.toEpochDays() * 86400L + start.time.hour * 3600L + start.time.minute * 60L + start.time.second
        val endEpoch = end.date.toEpochDays() * 86400L + end.time.hour * 3600L + end.time.minute * 60L + end.time.second
        return ((endEpoch - startEpoch) / 60).toInt()
    }

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
