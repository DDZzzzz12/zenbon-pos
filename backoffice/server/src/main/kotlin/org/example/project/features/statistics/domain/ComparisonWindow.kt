package org.example.project.features.statistics.domain

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A resolved baseline window.
 *
 * The repository filters a closing-attributed bill by `(start, end]` — start exclusive, as a Z-Bon
 * defines its period (emu21 NOTE-m) — so a baseline ending exactly at the current period's start
 * instant no longer shares that instant with it.
 */
data class ComparisonWindow(
    val start: LocalDateTime,
    val end: LocalDateTime,
)

/**
 * Derives the like-for-like baseline for a selected reporting window.
 *
 * Lives server-side on purpose: both the till and the web admin need the same answer, and a rule
 * duplicated in two clients drifts until two screens disagree about the same month. Pure date
 * arithmetic, so it is unit-testable without a database.
 *
 * DAY and WEEK are fixed-length, so a 7-day shift is the baseline for both the current partial
 * period and a complete past one — it preserves the span, giving same-weekday-last-week for DAY and
 * the previous (equally-truncated) week for WEEK. [complete] only branches MONTH and YEAR, which vary
 * in length: a whole past month/year compares to the whole previous one, while the current partial
 * period compares to the same elapsed span (month-to-date against a whole previous month would show
 * a collapse every month until the 28th).
 */
object ComparisonWindowResolver {
    fun resolve(
        start: LocalDateTime,
        end: LocalDateTime,
        granularity: String,
        complete: Boolean,
    ): ComparisonWindow? =
        when (granularity) {
            "DAY", "WEEK" -> ComparisonWindow(start.minusDays(7), end.minusDays(7))
            "MONTH" -> if (complete) wholePreviousMonth(start) else previousMonthSameElapsedDays(start, end)
            "YEAR" -> if (complete) wholePreviousYear(start) else previousYearSameElapsed(start, end)
            else -> null
        }

    /** The whole previous calendar month: [prev 1st 00:00, this 1st 00:00). */
    private fun wholePreviousMonth(start: LocalDateTime): ComparisonWindow {
        val prevStartDate = start.date.minus(DatePeriod(months = 1))
        val prevStart = LocalDateTime(LocalDate(prevStartDate.year, prevStartDate.month, 1), LocalTime(0, 0))
        val thisStart = LocalDateTime(LocalDate(start.year, start.month, 1), LocalTime(0, 0))
        return ComparisonWindow(prevStart, thisStart)
    }

    /** The whole previous calendar year: [prev Jan 1 00:00, this Jan 1 00:00). */
    private fun wholePreviousYear(start: LocalDateTime): ComparisonWindow {
        val prevStart = LocalDateTime(LocalDate(start.year - 1, Month.JANUARY, 1), LocalTime(0, 0))
        val thisStart = LocalDateTime(LocalDate(start.year, Month.JANUARY, 1), LocalTime(0, 0))
        return ComparisonWindow(prevStart, thisStart)
    }

    /** Previous year from Jan 1, same elapsed span — the year-to-date partial case. */
    private fun previousYearSameElapsed(
        start: LocalDateTime,
        end: LocalDateTime,
    ): ComparisonWindow {
        val elapsedDays = start.date.daysUntil(end.date)
        val prevStart = LocalDateTime(LocalDate(start.year - 1, Month.JANUARY, 1), LocalTime(0, 0))
        val prevEnd = LocalDateTime(prevStart.date.plus(DatePeriod(days = elapsedDays)), end.time)
        return ComparisonWindow(prevStart, prevEnd)
    }

    /**
     * The same days-of-month in the previous month, clamped to that month's length.
     *
     * 1–31 March must compare against 1 Feb – 1 Mar (a 28-day February), never roll into March.
     */
    private fun previousMonthSameElapsedDays(
        start: LocalDateTime,
        end: LocalDateTime,
    ): ComparisonWindow {
        val prevStartDate = start.date.minus(DatePeriod(months = 1))
        val elapsedDays = start.date.daysUntil(end.date)
        // Days remaining in prevStartDate's own month, not the whole month's length: those are
        // equal only when the start is the 1st, and nothing ties period=THIS_MONTH to that —
        // start_date, end_date and period are three independent query parameters.
        val prevMonthEnd =
            LocalDate(prevStartDate.year, prevStartDate.month, 1)
                .plus(DatePeriod(months = 1))
        val daysRemaining = prevStartDate.daysUntil(prevMonthEnd)
        val prevStart = LocalDateTime(prevStartDate, start.time)
        return if (elapsedDays < daysRemaining) {
            // Unclamped: carry end's time so an intraday "month so far" compares like for like.
            ComparisonWindow(
                prevStart,
                LocalDateTime(prevStartDate.plus(DatePeriod(days = elapsedDays)), end.time),
            )
        } else {
            // Clamped: hard-stop at the month boundary at midnight. Carrying end.time here would
            // push past it and reopen the March-rollover the clamp exists to prevent.
            ComparisonWindow(prevStart, LocalDateTime(prevMonthEnd, LocalTime(0, 0)))
        }
    }
}

private fun LocalDateTime.minusDays(days: Int) = LocalDateTime(date.minus(DatePeriod(days = days)), time)

/**
 * Signed percentage change from [previous] to [current], 2 dp, HALF_UP.
 *
 * Null when [previous] is zero: a percentage against zero is undefined, and rendering +100 %
 * against a period with no trade is worse than rendering nothing. The UI treats null as "show the
 * figure with no delta chip".
 */
fun deltaPercent(
    current: BigDecimal,
    previous: BigDecimal,
): String? {
    if (previous.compareTo(BigDecimal.ZERO) == 0) return null
    return current
        .subtract(previous)
        .multiply(BigDecimal("100"))
        .divide(previous, 2, RoundingMode.HALF_UP)
        .toPlainString()
}
