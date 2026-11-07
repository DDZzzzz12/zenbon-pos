package org.example.project.features.timetracking.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.timetracking.TimeEntryDto
import org.example.project.core.domain.enums.employee.ContractedHoursPeriod
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.employee.isTerminal
import org.example.project.core.domain.enums.timetracking.TimeEntryStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.durationBetween
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.employee.domain.repository.EmployeeInfoRepository
import org.example.project.features.employee.domain.repository.EmployeeRepository
import org.example.project.features.timetracking.domain.models.TimeBreak
import org.example.project.features.timetracking.domain.repository.TimeEntryRepository
import org.example.project.features.timetracking.domain.service.TimeTrackingService
import org.example.project.features.timetracking.domain.service.TimeValidationService
import org.example.project.features.timetracking.mapper.toActiveShift
import org.example.project.features.timetracking.mapper.toResponse
import org.example.project.features.timetracking.mapper.toSummary
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.formatter.currentTimeStamp
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.UUID

class TimeTrackingServiceImpl(
    private val timeEntryRepository: TimeEntryRepository,
    private val employeeRepository: EmployeeRepository,
    private val employeeInfoRepository: EmployeeInfoRepository,
    private val timeValidationService: TimeValidationService,
) : TimeTrackingService {
    private val logger = LoggerFactory.getLogger(TimeTrackingServiceImpl::class.java)

    override suspend fun clockIn(request: TimeEntryDto.ClockInRequestDto): TimeEntryDto.ActiveShiftDto =
        ExceptionConverter.catchingThrows(
            context = "clockIn(employeeNumber=${request.employeeNumber})",
            operation = "CLOCK_IN",
            entityType = "TIME_ENTRY",
            employeeId = request.employeeNumber,
        ) {
            dbQuery {
                val employee =
                    employeeRepository.getEmployeeByEmployeeNumber(request.employeeNumber)
                        ?: throw employeeNotFoundByNumber(request.employeeNumber)

                if (employee.role.isTerminal()) {
                    throw PosException(
                        PosErrorCode.AUTH_FORBIDDEN,
                        HttpStatusCode.Forbidden,
                        "Can not clock in for Terminal account",
                    )
                }

                if (employee.pin != request.pin) {
                    throw invalidCredentials()
                }

                val existing = timeEntryRepository.findActiveByEmployeeId(employee.id.value)
                if (existing != null) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.Conflict,
                        "Employee already has an active shift",
                    )
                }

                val now = currentTimeStamp()
                val costCenterId =
                    request.costCenterId?.let { UUID.fromString(it) }
                        ?: employee.defaultCostCenterId?.value

                val warnings = timeValidationService.validateRestPeriod(employee.id.value, now)
                if (warnings.isNotEmpty()) {
                    logger.warn(
                        "TimeTrackingServiceImpl.clockIn: clock-in warnings for employeeNumber={}, warnings={}",
                        employee.employeeNumber,
                        warnings,
                    )
                }

                val entry =
                    timeEntryRepository.create(
                        employeeId = employee.id.value,
                        date = now.date,
                        clockIn = now,
                        manualEntry = false,
                        costCenterId = costCenterId,
                        comment = request.comment,
                    )

                entry.toActiveShift()
            }
        }

    override suspend fun clockOut(request: TimeEntryDto.ClockOutRequestDto): TimeEntryDto.TimeEntryResponseDto =
        ExceptionConverter.catchingThrows(
            context = "clockOut(employeeNumber=${request.employeeNumber})",
            operation = "CLOCK_OUT",
            entityType = "TIME_ENTRY",
            employeeId = request.employeeNumber,
        ) {
            dbQuery {
                val employee =
                    employeeRepository.getEmployeeByEmployeeNumber(request.employeeNumber)
                        ?: throw employeeNotFoundByNumber(request.employeeNumber)

                if (employee.pin != request.pin) {
                    throw invalidCredentials()
                }

                val activeEntry =
                    timeEntryRepository.findActiveByEmployeeId(employee.id.value)
                        ?: throw PosException(
                            PosErrorCode.RESOURCE_NOT_FOUND,
                            HttpStatusCode.NotFound,
                            "No active shift found",
                        )

                // End any active break
                val activeBreak = activeEntry.breaks.firstOrNull { it.breakEnd == null }
                activeBreak?.let { it.breakEnd = currentTimeStamp() }

                val now = currentTimeStamp()
                request.comment?.let { activeEntry.comment = it }
                val updated =
                    timeEntryRepository.updateClockOut(activeEntry.id.value, now)
                        ?: throw PosException(
                            PosErrorCode.INTERNAL_ERROR,
                            HttpStatusCode.InternalServerError,
                            "Failed to update clock out",
                        )

                updated.toResponse(timeValidationService)
            }
        }

    override suspend fun startBreak(request: TimeEntryDto.BreakStartRequestDto): TimeEntryDto.ActiveShiftDto =
        ExceptionConverter.catchingThrows(
            context = "startBreak(employeeNumber=${request.employeeNumber})",
            operation = "START_BREAK",
            entityType = "TIME_ENTRY",
            employeeId = request.employeeNumber,
        ) {
            dbQuery {
                val employee =
                    employeeRepository.getEmployeeByEmployeeNumber(request.employeeNumber)
                        ?: throw employeeNotFoundByNumber(request.employeeNumber)

                if (employee.pin != request.pin) {
                    throw invalidCredentials()
                }

                val activeEntry =
                    timeEntryRepository.findActiveByEmployeeId(employee.id.value)
                        ?: throw PosException(
                            PosErrorCode.RESOURCE_NOT_FOUND,
                            HttpStatusCode.NotFound,
                            "No active shift found",
                        )

                val existingBreak = activeEntry.breaks.firstOrNull { it.breakEnd == null }
                if (existingBreak != null) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.Conflict,
                        "Break already in progress",
                    )
                }

                TimeBreak.new {
                    this.timeEntry = activeEntry
                    this.breakStart = currentTimeStamp()
                }

                activeEntry.toActiveShift()
            }
        }

    override suspend fun endBreak(request: TimeEntryDto.BreakEndRequestDto): TimeEntryDto.ActiveShiftDto =
        ExceptionConverter.catchingThrows(
            context = "endBreak(employeeNumber=${request.employeeNumber})",
            operation = "END_BREAK",
            entityType = "TIME_ENTRY",
            employeeId = request.employeeNumber,
        ) {
            dbQuery {
                val employee =
                    employeeRepository.getEmployeeByEmployeeNumber(request.employeeNumber)
                        ?: throw employeeNotFoundByNumber(request.employeeNumber)

                if (employee.pin != request.pin) {
                    throw invalidCredentials()
                }

                val activeEntry =
                    timeEntryRepository.findActiveByEmployeeId(employee.id.value)
                        ?: throw PosException(
                            PosErrorCode.RESOURCE_NOT_FOUND,
                            HttpStatusCode.NotFound,
                            "No active shift found",
                        )

                val activeBreak =
                    activeEntry.breaks.firstOrNull { it.breakEnd == null }
                        ?: throw PosException(
                            PosErrorCode.RESOURCE_NOT_FOUND,
                            HttpStatusCode.NotFound,
                            "No active break found",
                        )

                activeBreak.breakEnd = currentTimeStamp()

                activeEntry.toActiveShift()
            }
        }

    override suspend fun getActiveShifts(
        employeeId: String,
        seesEveryone: Boolean,
    ): List<TimeEntryDto.ActiveShiftDto> =
        dbQuery {
            val employee =
                Employee.findById(UUID.fromString(employeeId))
                    ?: throw employeeNotFoundById(employeeId)
            // TIMETRACKING_ALL_READ is what "everyone's entries" means — the capability the
            // shop-wide `/api/timetracking/all` route is gated by. It replaces
            // `role.isManagerOrHigher()`, which decided this from the employee row's role field and
            // therefore gave a purpose-built group whatever its role happened to say.
            //
            // The TERMINAL_DEVICE leg stays and is NOT a capability question: a shared station shows
            // the board of who is currently clocked in, which is the point of an unattended till
            // display. It asks "is this a station", the same distinction the client's nav filter and
            // the server's StationClass draw.
            val entries =
                if (employee.role == EmployeeRole.TERMINAL_DEVICE || seesEveryone) {
                    timeEntryRepository.findAllActive()
                } else {
                    listOfNotNull(timeEntryRepository.findActiveByEmployeeId(employee.id.value))
                }
            entries.map { it.toActiveShift() }
        }

    override suspend fun getMyEntries(
        employeeId: String,
        year: Int,
        month: Int,
    ): List<TimeEntryDto.TimeEntrySummaryDto> =
        dbQuery {
            val entries = timeEntryRepository.findByEmployeeAndMonth(UUID.fromString(employeeId), year, month)
            entries.map { it.toSummary(timeValidationService) }
        }

    override suspend fun getMonthlyOverview(
        employeeId: String,
        year: Int,
        month: Int,
    ): TimeEntryDto.MonthlyOverviewDto =
        dbQuery {
            val empId = UUID.fromString(employeeId)
            val employee =
                employeeRepository.getEmployeeById(empId)
                    ?: throw employeeNotFoundById(employeeId)

            val entries = timeEntryRepository.findByEmployeeAndMonth(empId, year, month)
            val totalWork =
                entries.sumOf { entry ->
                    val clockOut = entry.clockOut ?: currentTimeStamp()
                    val grossMinutes = minutesBetween(entry.clockIn, clockOut)
                    val stempledBreak =
                        entry.breaks.sumOf { b ->
                            val bEnd = b.breakEnd ?: currentTimeStamp()
                            minutesBetween(b.breakStart, bEnd)
                        }
                    val effectiveBreak = timeValidationService.calculateEffectiveBreak(grossMinutes, stempledBreak)
                    grossMinutes - effectiveBreak
                }

            val totalBreaks =
                entries.sumOf { entry ->
                    val clockOut = entry.clockOut ?: currentTimeStamp()
                    val grossMinutes = minutesBetween(entry.clockIn, clockOut)
                    val stempledBreak =
                        entry.breaks.sumOf { b ->
                            val bEnd = b.breakEnd ?: currentTimeStamp()
                            minutesBetween(b.breakStart, bEnd)
                        }
                    timeValidationService.calculateEffectiveBreak(grossMinutes, stempledBreak)
                }

            val info = employeeInfoRepository.getEmployeeInfoByEmployeeId(UUID.fromString(employeeId))
            val weeklyHours = info?.contractedWeeklyHours
            val minutesPerHour = BigDecimal(60)
            val targetMonthly =
                info?.contractedHours?.let { hours ->
                    val monthlyHours =
                        when (info.contractedHoursPeriod) {
                            ContractedHoursPeriod.MONTHLY -> hours
                            else -> hours * BigDecimal(ContractedHoursPeriod.weeksInMonth(year, month))
                        }
                    (monthlyHours * minutesPerHour).toInt()
                }
            val overtime = if (targetMonthly != null) maxOf(0, totalWork - targetMonthly) else 0

            val workingDays = entries.map { it.date }.distinct().size

            val approvalStatus = entries.firstOrNull()?.status?.name ?: TimeEntryStatus.DRAFT.name

            TimeEntryDto.MonthlyOverviewDto(
                employeeId = employeeId,
                employeeName = employee.fullName,
                year = year,
                month = month,
                contractedWeeklyHours = weeklyHours,
                targetMonthlyMinutes = targetMonthly,
                actualWorkMinutes = totalWork,
                overtimeMinutes = overtime,
                totalBreakMinutes = totalBreaks,
                workingDays = workingDays,
                status = approvalStatus,
            )
        }

    override suspend fun createManualEntry(
        request: TimeEntryDto.ManualEntryRequestDto,
        createdByEmployeeId: String,
    ): TimeEntryDto.TimeEntryResponseDto =
        ExceptionConverter.catchingThrows(
            context = "createManualEntry(employeeId=${request.employeeId}, createdBy=$createdByEmployeeId)",
            employeeId = createdByEmployeeId,
            operation = "CREATE_MANUAL_TIME_ENTRY",
            entityType = "TIME_ENTRY",
        ) {
            try {
                request.validate()
            } catch (e: IllegalArgumentException) {
                throw validationFailed(e.message ?: "Invalid request")
            }
            dbQuery {
                val date = LocalDate.parse(request.date)
                val startTime = LocalDateTime.parse(request.startTime)
                val endTime = LocalDateTime.parse(request.endTime)
                val costCenterId = request.costCenterId?.let { UUID.fromString(it) }

                val entry =
                    timeEntryRepository.create(
                        employeeId = UUID.fromString(request.employeeId),
                        date = date,
                        clockIn = startTime,
                        manualEntry = true,
                        costCenterId = costCenterId,
                        comment = request.comment,
                    )
                entry.clockOut = endTime

                // Create break if specified
                if (request.breakMinutes > 0) {
                    val breakEnd =
                        LocalDateTime(
                            startTime.date,
                            kotlinx.datetime.LocalTime(
                                startTime.hour,
                                startTime.minute + request.breakMinutes,
                            ),
                        )
                    TimeBreak.new {
                        this.timeEntry = entry
                        this.breakStart = startTime
                        this.breakEnd = breakEnd
                    }
                }

                entry.toResponse(timeValidationService)
            }
        }

    override suspend fun updateEntry(
        entryId: String,
        request: TimeEntryDto.UpdateEntryRequestDto,
    ): TimeEntryDto.TimeEntryResponseDto =
        dbQuery {
            val id = UUID.fromString(entryId)
            val entry =
                timeEntryRepository.findById(id)
                    ?: throw PosException(
                        PosErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "Time entry not found",
                    )

            if (entry.status != TimeEntryStatus.DRAFT) {
                throw PosException(
                    PosErrorCode.INVALID_REQUEST,
                    HttpStatusCode.Conflict,
                    "Can only edit DRAFT entries",
                )
            }

            val updated =
                timeEntryRepository.update(
                    id = id,
                    clockIn = request.startTime?.let { LocalDateTime.parse(it) },
                    clockOut = request.endTime?.let { LocalDateTime.parse(it) },
                    costCenterId = request.costCenterId?.let { UUID.fromString(it) },
                    comment = request.comment,
                ) ?: throw PosException(
                    PosErrorCode.INTERNAL_ERROR,
                    HttpStatusCode.InternalServerError,
                    "Update failed",
                )

            updated.toResponse(timeValidationService)
        }

    override suspend fun deleteEntry(entryId: String) {
        ExceptionConverter.catchingThrows(
            context = "deleteEntry(entryId=$entryId)",
            operation = "DELETE_TIME_ENTRY",
            entityType = "TIME_ENTRY",
        ) {
            dbQuery {
                val id = UUID.fromString(entryId)
                val entry =
                    timeEntryRepository.findById(id)
                        ?: throw PosException(
                            PosErrorCode.RESOURCE_NOT_FOUND,
                            HttpStatusCode.NotFound,
                            "Time entry not found",
                        )

                if (entry.status != TimeEntryStatus.DRAFT) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.Conflict,
                        "Can only delete DRAFT entries",
                    )
                }

                timeEntryRepository.delete(id)
            }
        }
    }

    override suspend fun getTeamEntries(
        year: Int,
        month: Int,
    ): List<TimeEntryDto.TimeEntrySummaryDto> =
        dbQuery {
            val entries = timeEntryRepository.findByMonth(year, month)
            entries.map { it.toSummary(timeValidationService) }
        }

    override suspend fun getAllEntries(
        year: Int,
        month: Int,
    ): List<TimeEntryDto.TimeEntrySummaryDto> = getTeamEntries(year, month)

    override suspend fun getAuthenticatedEntries(request: TimeEntryDto.AuthenticatedEntriesRequestDto): List<TimeEntryDto.TimeEntrySummaryDto> =
        dbQuery {
            val employee =
                employeeRepository.getEmployeeByEmployeeNumber(request.employeeNumber)
                    ?: throw employeeNotFoundByNumber(request.employeeNumber)

            if (employee.pin != request.pin) {
                throw invalidCredentials()
            }

            val entries = timeEntryRepository.findByEmployeeAndMonth(employee.id.value, request.year, request.month)
            entries.map { it.toSummary(timeValidationService) }
        }

    private fun minutesBetween(
        start: LocalDateTime,
        end: LocalDateTime,
    ): Int = durationBetween(start, end).inWholeMinutes.toInt()

    private fun employeeNotFoundByNumber(num: String) =
        PosException(
            PosErrorCode.EMPLOYEE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Employee #$num not found",
        )

    private fun employeeNotFoundById(id: String) =
        PosException(
            PosErrorCode.EMPLOYEE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Employee with id=$id not found",
        )

    private fun invalidCredentials() =
        PosException(
            PosErrorCode.AUTH_INVALID_CREDENTIALS,
            HttpStatusCode.Unauthorized,
            "Invalid credentials",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
