package org.example.project.features.datevexportlog.data.service

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import org.example.project.core.domain.enums.setting.DatevExportCadence

data class Period(
    val start: LocalDateTime,
    val end: LocalDateTime,
    val label: String,
)

object PeriodEnumerator {
    fun missingPeriods(
        cursor: LocalDateTime?,
        now: LocalDateTime,
        cadence: DatevExportCadence,
        tz: TimeZone = TimeZone.currentSystemDefault(),
    ): List<Period> {
        if (cadence == DatevExportCadence.MANUAL) return emptyList()

        return when (cadence) {
            DatevExportCadence.WEEKLY -> missingWeeklyPeriods(cursor, now)
            DatevExportCadence.MONTHLY -> missingMonthlyPeriods(cursor, now)
            DatevExportCadence.MANUAL -> emptyList()
        }
    }

    private fun missingWeeklyPeriods(
        cursor: LocalDateTime?,
        now: LocalDateTime,
    ): List<Period> {
        val today = now.date
        val currentWeekStart = mondayOf(today)
        // "current incomplete week" starts at currentWeekStart; its end would be currentWeekStart + 7 days
        // We only include periods whose end <= currentWeekStart (i.e., fully completed before today's week)

        if (cursor == null) {
            // Bootstrap: return only the single most recently completed week
            val lastWeekStart = currentWeekStart.plus(DatePeriod(days = -7))
            val lastWeekEnd = currentWeekStart
            return listOf(weekPeriod(lastWeekStart, lastWeekEnd))
        }

        // The cursor is the exclusive end of the last exported period.
        // If cursor is exactly on a Monday (week boundary), that Monday is the start of the next period to export.
        // If cursor is mid-week, advance to the next Monday.
        val cursorDate = cursor.date
        val cursorWeekStart = mondayOf(cursorDate)
        val walkStart =
            if (cursorDate == cursorWeekStart && cursor.time == LocalTime(0, 0)) {
                // Cursor is exactly at a week boundary → next period starts here
                cursorWeekStart
            } else {
                // Cursor is mid-week → skip the rest of this week, start at next Monday
                cursorWeekStart.plus(DatePeriod(days = 7))
            }

        val result = mutableListOf<Period>()
        var weekStart = walkStart
        while (true) {
            val weekEnd = weekStart.plus(DatePeriod(days = 7))
            // Only include if the period ended at or before the start of the current incomplete week
            if (weekEnd > currentWeekStart) break
            result.add(weekPeriod(weekStart, weekEnd))
            weekStart = weekEnd
        }
        return result
    }

    private fun missingMonthlyPeriods(
        cursor: LocalDateTime?,
        now: LocalDateTime,
    ): List<Period> {
        val today = now.date

        @Suppress("DEPRECATION")
        val currentMonthStart = LocalDate(today.year, today.monthNumber, 1)

        if (cursor == null) {
            // Bootstrap: return only the single most recently completed month
            val lastMonthStart = currentMonthStart.plus(DatePeriod(months = -1))
            val lastMonthEnd = currentMonthStart
            return listOf(monthPeriod(lastMonthStart, lastMonthEnd))
        }

        // The cursor is the exclusive end of the last exported period.
        // If cursor is exactly on the first day of a month (at midnight), that month start is the next period.
        // If cursor is mid-month, advance to the next month start.
        val cursorDate = cursor.date

        @Suppress("DEPRECATION")
        val cursorMonthStart = LocalDate(cursorDate.year, cursorDate.monthNumber, 1)
        val walkStart =
            if (cursorDate == cursorMonthStart && cursor.time == LocalTime(0, 0)) {
                // Cursor is exactly at a month boundary → next period starts here
                cursorMonthStart
            } else {
                // Cursor is mid-month → skip the rest of this month, start at next month
                cursorMonthStart.plus(DatePeriod(months = 1))
            }

        val result = mutableListOf<Period>()
        var monthStart = walkStart
        while (true) {
            val monthEnd = monthStart.plus(DatePeriod(months = 1))
            // Only include if the period ended at or before the start of the current incomplete month
            if (monthEnd > currentMonthStart) break
            result.add(monthPeriod(monthStart, monthEnd))
            monthStart = monthEnd
        }
        return result
    }

    private fun mondayOf(date: LocalDate): LocalDate {
        val daysFromMonday = date.dayOfWeek.isoDayNumber - 1
        return date.plus(DatePeriod(days = -daysFromMonday))
    }

    private fun isoWeekLabel(date: LocalDate): String {
        @Suppress("DEPRECATION")
        val jd = java.time.LocalDate.of(date.year, date.monthNumber, date.dayOfMonth)
        val wf = java.time.temporal.WeekFields.ISO
        val week = jd.get(wf.weekOfWeekBasedYear())
        val year = jd.get(wf.weekBasedYear())
        return "%04d-W%02d".format(year, week)
    }

    private fun weekPeriod(
        weekStart: LocalDate,
        weekEnd: LocalDate,
    ): Period {
        val start = LocalDateTime(weekStart, LocalTime(0, 0))
        val end = LocalDateTime(weekEnd, LocalTime(0, 0))
        val label = isoWeekLabel(weekStart)
        return Period(start, end, label)
    }

    private fun monthPeriod(
        monthStart: LocalDate,
        monthEnd: LocalDate,
    ): Period {
        val start = LocalDateTime(monthStart, LocalTime(0, 0))
        val end = LocalDateTime(monthEnd, LocalTime(0, 0))

        @Suppress("DEPRECATION")
        val label = "%04d-%02d".format(monthStart.year, monthStart.monthNumber)
        return Period(start, end, label)
    }
}
