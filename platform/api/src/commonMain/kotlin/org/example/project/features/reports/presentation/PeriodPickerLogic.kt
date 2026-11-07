package org.example.project.features.reports.presentation

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Mon-first grid of the visible weeks of [year]/[month] as a flat list of 35 or 42 cells.
 * Leading/trailing days that fall outside the month are null-padded so every row is a full
 * Mon–Sun week. Cell index 0 is always the Monday before or on the 1st.
 */
fun monthGrid(
    year: Int,
    month: Month,
): List<LocalDate?> {
    val first = LocalDate(year, month, 1)
    val lastDay = first.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1)).day
    val lead = first.dayOfWeek.ordinal // Mon = 0
    val grid = ArrayList<LocalDate?>(42)
    repeat(lead) { grid += null }
    for (day in 1..lastDay) grid += LocalDate(year, month, day)
    while (grid.size % 7 != 0) grid += null
    return grid
}

/** True when [date] lies after [today]. */
fun isFutureDay(
    date: LocalDate,
    today: LocalDate,
): Boolean = date > today

/** True when the ISO week containing [date] starts after [today] (Monday of [today]'s week is ok). */
fun isFutureWeek(
    date: LocalDate,
    today: LocalDate,
): Boolean = weekStart(date) > today

/** True when the month [year]/[month] lies after the month [today] falls in. */
fun isFutureMonth(
    year: Int,
    month: Month,
    today: LocalDate,
): Boolean = year > today.year || (year == today.year && month > today.month)

/** True when [year] lies after [today]'s year. */
fun isFutureYear(
    year: Int,
    today: LocalDate,
): Boolean = year > today.year

/** The 10-year window [year] belongs to, e.g. 2026 -> (2020, 2029). */
fun decadeFor(year: Int): Pair<Int, Int> {
    val start = year / 10 * 10
    return start to start + 9
}
