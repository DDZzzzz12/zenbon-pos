package org.example.project.features.contract.data.service

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.example.project.features.contract.domain.models.ContractInterval
import org.example.project.features.contract.domain.models.ScheduleStep

/**
 * Turns a contract's start date, interval and due day into the dates that fall due inside a window.
 *
 * **Pure, and the window is an argument — there is no clock in here.** Every bug in contract
 * handling lives in this arithmetic (February has no 31st, quarterly counts from the contract's own
 * start month rather than from January, an end date mid-period must not yield one instalment too
 * many), so it is kept free of the database and of `now()` and each case is a two-line test.
 *
 * A due day past the end of a month **clamps to the last day** rather than skipping the month:
 * skipping would silently lose an instalment, and the rent is still owed in February.
 */
object InstalmentSchedule {
    /**
     * The dates on which this contract falls due within `[windowStart, windowEnd]`, ascending.
     *
     * A date is kept when it is on or after [startDate], on or before [endDate] when one is set,
     * and inside the window. Building stops as soon as a period runs past the window, so the list
     * is never generated unbounded and filtered afterwards.
     *
     * [dueWeekday] is ISO-8601 (1 = Monday … 7 = Sunday) and is required for — and only used by —
     * [ContractInterval.WEEKLY]. Every other interval anchors on [dueDay].
     */
    fun dueDatesIn(
        startDate: LocalDate,
        endDate: LocalDate?,
        interval: ContractInterval,
        dueDay: Int,
        dueWeekday: Int?,
        windowStart: LocalDate,
        windowEnd: LocalDate,
    ): List<LocalDate> {
        // A dueDay of 0 or 32 would silently produce no dates at all, and a contract that generates
        // nothing looks exactly like a contract nobody set up.
        require(dueDay in 1..31) { "dueDay must be between 1 and 31, was $dueDay" }
        if (windowStart > windowEnd) return emptyList()

        val lastDate = endDate?.let { minOf(it, windowEnd) } ?: windowEnd

        return when (val step = interval.step) {
            is ScheduleStep.Once -> {
                val due = dueDateInMonth(LocalDate(startDate.year, startDate.month, 1), dueDay)
                if (due in startDate..lastDate && due >= windowStart) listOf(due) else emptyList()
            }

            is ScheduleStep.Days -> {
                // Same failure mode as a bad dueDay: a null weekday would quietly yield no dates.
                require(dueWeekday in 1..7) {
                    "a WEEKLY contract needs a dueWeekday between 1 and 7, was $dueWeekday"
                }
                val first = firstOnOrAfter(startDate, requireNotNull(dueWeekday))
                buildDates(first, lastDate, windowStart) { it.plus(step.count, DateTimeUnit.DAY) }
            }

            is ScheduleStep.Months -> {
                // Period-counted rather than date-walked, because a due day past the end of a month
                // clamps: stepping from the clamped date would drift (31 Jan -> 28 Feb -> 28 Mar).
                val firstMonth = LocalDate(startDate.year, startDate.month, 1)
                val dates = mutableListOf<LocalDate>()
                var period = 0
                var due = dueDateInMonth(firstMonth, dueDay)
                while (due <= lastDate) {
                    if (due >= startDate && due >= windowStart) dates += due
                    period++
                    due = dueDateInMonth(firstMonth.plus(period * step.count, DateTimeUnit.MONTH), dueDay)
                }
                dates
            }
        }
    }

    /** The first [weekday] (ISO 1=Mon…7=Sun) on or after [from]. */
    private fun firstOnOrAfter(
        from: LocalDate,
        weekday: Int,
    ): LocalDate {
        val offset = (weekday - from.dayOfWeek.isoDayNumber + 7) % 7
        return from.plus(offset, DateTimeUnit.DAY)
    }

    /** Walks [next] from [first] until past [lastDate], keeping what is at or after [windowStart]. */
    private fun buildDates(
        first: LocalDate,
        lastDate: LocalDate,
        windowStart: LocalDate,
        next: (LocalDate) -> LocalDate,
    ): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var cursor = first
        while (cursor <= lastDate) {
            if (cursor >= windowStart) dates += cursor
            cursor = next(cursor)
        }
        return dates
    }

    /** [dueDay] inside [monthStart]'s month, clamped to that month's last day. */
    private fun dueDateInMonth(
        monthStart: LocalDate,
        dueDay: Int,
    ): LocalDate = monthStart.plus(minOf(dueDay, lengthOfMonth(monthStart)) - 1, DateTimeUnit.DAY)

    private fun lengthOfMonth(monthStart: LocalDate): Int =
        monthStart
            .plus(1, DateTimeUnit.MONTH)
            .minus(1, DateTimeUnit.DAY)
            .day
}
