package org.example.project.features.timetracking.domain.service

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import java.util.UUID

interface TimeValidationService {
    fun validateDailyHours(
        employeeId: UUID,
        date: LocalDate,
        totalMinutes: Int,
    ): List<String>

    fun validateRestPeriod(
        employeeId: UUID,
        clockIn: LocalDateTime,
    ): List<String>

    fun calculateEffectiveBreak(
        grossWorkMinutes: Int,
        stempledBreakMinutes: Int,
    ): Int
}
