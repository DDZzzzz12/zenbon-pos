package org.example.project.core.utils

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.enums.scheduler.JobFrequency
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Client-side calculator for the next scheduled run time.
 * Uses kotlinx.datetime for multiplatform compatibility.
 *
 * The server-side counterpart uses java.time and lives in pos-server.
 * This copy is for UI preview ("Next run: ...") in the form dialog.
 */
@OptIn(ExperimentalTime::class)
object NextRunCalculator {
    /**
     * Computes the next run instant strictly after [reference].
     *
     * @param frequency DAILY, WEEKLY, or MONTHLY
     * @param timeOfDay "HH:MM" string
     * @param dayOfWeek 1..7, Monday=1. Required for WEEKLY.
     * @param dayOfMonth 1..28 or -1 for last day of month. Required for MONTHLY.
     * @param timezone Target timezone
     * @param reference Reference instant (default: now)
     */
    fun next(
        frequency: JobFrequency,
        timeOfDay: String,
        dayOfWeek: Int? = null,
        dayOfMonth: Int? = null,
        timezone: TimeZone = TimeZone.of("Europe/Berlin"),
        reference: Instant = Clock.System.now(),
    ): Instant {
        val (hour, minute) = parseHHmm(timeOfDay)
        val refLocal = reference.toLocalDateTime(timezone)

        return when (frequency) {
            JobFrequency.DAILY -> {
                val candidate = LocalDateTime(refLocal.date, LocalTime(hour, minute))
                val candidateInstant = candidate.toInstant(timezone)
                if (candidateInstant > reference) {
                    candidateInstant
                } else {
                    LocalDateTime(refLocal.date.plus(DatePeriod(days = 1)), LocalTime(hour, minute))
                        .toInstant(timezone)
                }
            }

            JobFrequency.WEEKLY -> {
                requireNotNull(dayOfWeek) { "dayOfWeek required for WEEKLY" }
                val currentIso = dayOfWeekToIso(refLocal.date.dayOfWeek)
                val daysAhead = (dayOfWeek - currentIso + 7) % 7
                val date = refLocal.date.plus(DatePeriod(days = daysAhead))
                val candidate = LocalDateTime(date, LocalTime(hour, minute))
                val candidateInstant = candidate.toInstant(timezone)
                if (candidateInstant > reference) {
                    candidateInstant
                } else {
                    LocalDateTime(date.plus(DatePeriod(days = 7)), LocalTime(hour, minute))
                        .toInstant(timezone)
                }
            }

            JobFrequency.MONTHLY -> {
                requireNotNull(dayOfMonth) { "dayOfMonth required for MONTHLY" }
                val candidate = buildMonthlyCandidate(refLocal.date.year, refLocal.month, hour, minute, dayOfMonth)
                val candidateInstant = candidate.toInstant(timezone)
                if (candidateInstant > reference) {
                    candidateInstant
                } else {
                    val nextMonthDate = refLocal.date.plus(DatePeriod(months = 1))
                    buildMonthlyCandidate(nextMonthDate.year, nextMonthDate.month, hour, minute, dayOfMonth)
                        .toInstant(timezone)
                }
            }
        }
    }

    private fun buildMonthlyCandidate(
        year: Int,
        month: Month,
        hour: Int,
        minute: Int,
        dayOfMonth: Int,
    ): LocalDateTime {
        val daysInMonth = daysInMonth(year, month)
        val dom = if (dayOfMonth == -1) daysInMonth else minOf(dayOfMonth, daysInMonth)
        return LocalDateTime(year, month, dom, hour, minute)
    }

    /** Returns ISO day number: Monday=1 .. Sunday=7. */
    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    private fun dayOfWeekToIso(dow: DayOfWeek): Int =
        when (dow) {
            DayOfWeek.MONDAY -> 1
            DayOfWeek.TUESDAY -> 2
            DayOfWeek.WEDNESDAY -> 3
            DayOfWeek.THURSDAY -> 4
            DayOfWeek.FRIDAY -> 5
            DayOfWeek.SATURDAY -> 6
            DayOfWeek.SUNDAY -> 7
            else -> 1
        }

    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    private fun daysInMonth(
        year: Int,
        month: Month,
    ): Int =
        when (month) {
            Month.JANUARY, Month.MARCH, Month.MAY, Month.JULY,
            Month.AUGUST, Month.OCTOBER, Month.DECEMBER,
            -> 31
            Month.APRIL, Month.JUNE, Month.SEPTEMBER, Month.NOVEMBER -> 30
            Month.FEBRUARY -> if (isLeapYear(year)) 29 else 28
            else -> 30
        }

    private fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    private fun parseHHmm(s: String): Pair<Int, Int> {
        val parts = s.split(":")
        require(parts.size == 2) { "timeOfDay must be HH:MM, got: $s" }
        val h = parts[0].trim().toInt()
        val m = parts[1].trim().toInt()
        require(h in 0..23 && m in 0..59) { "timeOfDay out of range: $s" }
        return h to m
    }
}
