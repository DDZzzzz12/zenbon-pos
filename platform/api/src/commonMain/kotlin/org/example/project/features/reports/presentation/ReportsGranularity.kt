package org.example.project.features.reports.presentation

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.ReportsKeys

/**
 * The reporting granularity the owner toggles between. `.name` is sent verbatim to the server as the
 * `granularity` parameter (DAY|WEEK|MONTH|YEAR) and must match `ComparisonWindowResolver`'s `when`,
 * so do not rename these without changing the server too.
 *
 * A period is identified by this granularity plus an anchor date; the stepper moves the anchor.
 */
enum class ReportsGranularity(
    val labelKey: StringKey,
) {
    DAY(ReportsKeys.granDay),
    WEEK(ReportsKeys.granWeek),
    MONTH(ReportsKeys.granMonth),
    YEAR(ReportsKeys.granYear),

    /**
     * A free from–to span rather than an anchored period. Used only by the document archive, which
     * holds its own `customFrom`/`customTo`; the reporting screens must never see it, because
     * `.name` is sent verbatim to the server as the `granularity` parameter and `CUSTOM` is not a
     * value `ComparisonWindowResolver` accepts.
     */
    CUSTOM(ReportsKeys.granCustom),
    ;

    companion object {
        /** The four anchored periods — everything the reporting screens may offer. */
        val reporting: List<ReportsGranularity> = listOf(DAY, WEEK, MONTH, YEAR)
    }
}

/** Monday of the ISO week containing [date] — `dayOfWeek.ordinal` is 0 for Monday. */
public fun weekStart(date: LocalDate): LocalDate = date.minus(DatePeriod(days = date.dayOfWeek.ordinal))

/**
 * The calendar first and last day of the period at this granularity anchored at [anchor] — no time
 * component, no notion of "now". Used both by [windowFor] (which adds the live-period/now handling on
 * top) and directly by callers, like the document archive, that only ever want the closed calendar
 * range regardless of whether that period happens to be the one currently in progress.
 */
fun ReportsGranularity.calendarBounds(anchor: LocalDate): Pair<LocalDate, LocalDate> =
    when (this) {
        ReportsGranularity.DAY -> anchor to anchor
        ReportsGranularity.WEEK -> {
            val monday = weekStart(anchor)
            monday to monday.plus(DatePeriod(days = 6))
        }
        ReportsGranularity.MONTH -> {
            val first = LocalDate(anchor.year, anchor.month, 1)
            val lastDay = first.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
            first to lastDay
        }
        ReportsGranularity.YEAR ->
            LocalDate(anchor.year, Month.JANUARY, 1) to LocalDate(anchor.year, Month.DECEMBER, 31)
        ReportsGranularity.CUSTOM -> error("CUSTOM has no anchored window; use the caller's from/to range")
    }

/**
 * The `[startIso, endIso]` window for this granularity anchored at [anchor]. The end is [now] for the
 * current, in-progress period and the calendar end for a past one — the server uses that to pick a
 * whole-period versus same-elapsed baseline. Both bounds carry a time component; the statistics
 * endpoint parses `LocalDateTime`.
 */
fun ReportsGranularity.windowFor(
    anchor: LocalDate,
    now: LocalDateTime,
): Pair<String, String> {
    val (calendarStart, calendarEnd) = calendarBounds(anchor)
    val start = LocalDateTime(calendarStart, LocalTime(0, 0))
    val end = if (isCurrentPeriod(anchor, now.date)) now else LocalDateTime(calendarEnd, LocalTime(23, 59, 59))
    return start.toString() to end.toString()
}

/** Moves [anchor] by [units] periods of this granularity (negative = back, positive = forward). */
fun ReportsGranularity.stepped(
    anchor: LocalDate,
    units: Int,
): LocalDate =
    when (this) {
        ReportsGranularity.DAY -> shiftDays(anchor, units)
        ReportsGranularity.WEEK -> shiftDays(anchor, units * 7)
        ReportsGranularity.MONTH -> shiftMonths(anchor, units)
        ReportsGranularity.YEAR -> shiftMonths(anchor, units * 12)
        ReportsGranularity.CUSTOM -> error("CUSTOM cannot be stepped; the caller owns its from/to range")
    }

private fun shiftDays(
    anchor: LocalDate,
    days: Int,
): LocalDate = if (days >= 0) anchor.plus(DatePeriod(days = days)) else anchor.minus(DatePeriod(days = -days))

private fun shiftMonths(
    anchor: LocalDate,
    months: Int,
): LocalDate = if (months >= 0) anchor.plus(DatePeriod(months = months)) else anchor.minus(DatePeriod(months = -months))

/** True when [anchor]'s period at this granularity is the one [today] falls in — the live period. */
fun ReportsGranularity.isCurrentPeriod(
    anchor: LocalDate,
    today: LocalDate,
): Boolean =
    when (this) {
        ReportsGranularity.DAY -> anchor == today
        ReportsGranularity.WEEK -> weekStart(anchor) == weekStart(today)
        ReportsGranularity.MONTH -> anchor.year == today.year && anchor.month == today.month
        ReportsGranularity.YEAR -> anchor.year == today.year
        ReportsGranularity.CUSTOM -> false
    }

private val MONTH_NAMES: Map<Language, List<String>> =
    mapOf(
        Language.GERMAN to
            listOf(
                "Januar",
                "Februar",
                "März",
                "April",
                "Mai",
                "Juni",
                "Juli",
                "August",
                "September",
                "Oktober",
                "November",
                "Dezember",
            ),
        Language.ENGLISH to
            listOf(
                "January",
                "February",
                "March",
                "April",
                "May",
                "June",
                "July",
                "August",
                "September",
                "October",
                "November",
                "December",
            ),
        Language.CHINESE to
            listOf(
                "一月",
                "二月",
                "三月",
                "四月",
                "五月",
                "六月",
                "七月",
                "八月",
                "九月",
                "十月",
                "十一月",
                "十二月",
            ),
    )

private fun LocalDate.d2(): String = day.toString().padStart(2, '0')

private fun LocalDate.m2(): String = (month.ordinal + 1).toString().padStart(2, '0')

/**
 * The human label for the period at this granularity anchored at [anchor], in [language].
 *
 * WEEK shows the Monday–Sunday span rather than an ISO week number — clearer for a back office and it
 * sidesteps the fiddly ISO-week computation, which kotlinx-datetime 0.8.0 does not expose directly.
 */
fun ReportsGranularity.formatAnchor(
    anchor: LocalDate,
    language: Language,
): String {
    val months = MONTH_NAMES[language] ?: MONTH_NAMES.getValue(Language.ENGLISH)
    return when (this) {
        ReportsGranularity.DAY -> "${anchor.d2()}.${anchor.m2()}.${anchor.year}"
        ReportsGranularity.WEEK -> {
            val monday = weekStart(anchor)
            val sunday = monday.plus(DatePeriod(days = 6))
            "${monday.d2()}.${monday.m2()}. – ${sunday.d2()}.${sunday.m2()}.${sunday.year}"
        }
        ReportsGranularity.MONTH -> "${months[anchor.month.ordinal]} ${anchor.year}"
        ReportsGranularity.YEAR -> anchor.year.toString()
        ReportsGranularity.CUSTOM -> ""
    }
}
