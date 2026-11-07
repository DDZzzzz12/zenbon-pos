package org.example.project.features.timetracking.mapper

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.timetracking.TimeEntryDto
import org.example.project.features.timetracking.domain.models.TimeBreak
import org.example.project.features.timetracking.domain.models.TimeEntry
import org.example.project.features.timetracking.domain.service.TimeValidationService
import org.example.project.utils.formatter.currentTimeStamp

fun TimeEntry.toResponse(timeValidationService: TimeValidationService? = null): TimeEntryDto.TimeEntryResponseDto {
    val breakList = breaks.map { it.toBreakResponse() }
    val stempledBreak = calculateTotalBreakMinutes(breaks)
    val grossMinutes = calculateGrossMinutes(clockIn, clockOut)
    val effectiveBreak = timeValidationService?.calculateEffectiveBreak(grossMinutes, stempledBreak) ?: stempledBreak
    val netWork = maxOf(0, grossMinutes - effectiveBreak)

    return TimeEntryDto.TimeEntryResponseDto(
        id = id.value.toString(),
        employeeId = employee.id.value.toString(),
        employeeName = employee.fullName,
        employeeNumber = employee.employeeNumber,
        date = date.toString(),
        clockIn = clockIn.toString(),
        clockOut = clockOut?.toString(),
        breaks = breakList,
        totalBreakMinutes = effectiveBreak,
        netWorkMinutes = netWork,
        manualEntry = manualEntry,
        costCenterId = costCenter?.id?.value?.toString(),
        costCenterCode = costCenter?.code,
        costCenterName = costCenter?.name,
        comment = comment,
        status = status.name,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )
}

fun TimeEntry.toSummary(timeValidationService: TimeValidationService? = null): TimeEntryDto.TimeEntrySummaryDto {
    val stempledBreak = calculateTotalBreakMinutes(breaks)
    val grossMinutes = calculateGrossMinutes(clockIn, clockOut)
    val effectiveBreak = timeValidationService?.calculateEffectiveBreak(grossMinutes, stempledBreak) ?: stempledBreak
    val netWork = maxOf(0, grossMinutes - effectiveBreak)

    return TimeEntryDto.TimeEntrySummaryDto(
        id = id.value.toString(),
        employeeId = employee.id.value.toString(),
        employeeName = employee.fullName,
        date = date.toString(),
        clockIn = clockIn.toString(),
        clockOut = clockOut?.toString(),
        totalBreakMinutes = effectiveBreak,
        netWorkMinutes = netWork,
        costCenterName = costCenter?.name,
        status = status.name,
        manualEntry = manualEntry,
    )
}

fun TimeEntry.toActiveShift(): TimeEntryDto.ActiveShiftDto {
    val now = currentTimeStamp()
    val totalBreak = calculateTotalBreakMinutes(breaks)
    val elapsed = minutesBetween(clockIn, now)
    val activeBreak = breaks.firstOrNull { it.breakEnd == null }

    return TimeEntryDto.ActiveShiftDto(
        id = id.value.toString(),
        employeeId = employee.id.value.toString(),
        employeeName = employee.fullName,
        employeeNumber = employee.employeeNumber,
        clockIn = clockIn.toString(),
        elapsedMinutes = elapsed,
        isOnBreak = activeBreak != null,
        currentBreakStartedAt = activeBreak?.breakStart?.toString(),
        totalBreakMinutes = totalBreak,
        costCenterName = costCenter?.name,
    )
}

fun TimeBreak.toBreakResponse(): TimeEntryDto.BreakResponseDto {
    val duration = if (breakEnd != null) minutesBetween(breakStart, breakEnd!!) else null
    return TimeEntryDto.BreakResponseDto(
        id = id.value.toString(),
        breakStart = breakStart.toString(),
        breakEnd = breakEnd?.toString(),
        durationMinutes = duration,
    )
}

private fun calculateTotalBreakMinutes(breaks: Iterable<TimeBreak>): Int {
    val now = currentTimeStamp()
    return breaks.sumOf { b ->
        val end = b.breakEnd ?: now
        minutesBetween(b.breakStart, end)
    }
}

private fun calculateGrossMinutes(
    clockIn: LocalDateTime,
    clockOut: LocalDateTime?,
): Int {
    val end = clockOut ?: currentTimeStamp()
    return minutesBetween(clockIn, end)
}

private fun minutesBetween(
    start: LocalDateTime,
    end: LocalDateTime,
): Int {
    val startEpoch = start.toEpochSeconds()
    val endEpoch = end.toEpochSeconds()
    return ((endEpoch - startEpoch) / 60).toInt()
}

private fun LocalDateTime.toEpochSeconds(): Long {
    val date = this.date
    val time = this.time
    val daysSinceEpoch = date.toEpochDays().toLong()
    return daysSinceEpoch * 86400L + time.hour * 3600L + time.minute * 60L + time.second
}
