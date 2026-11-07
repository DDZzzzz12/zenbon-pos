package org.example.project.features.reports

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.example.project.features.reports.presentation.ReportsGranularity
import org.example.project.features.reports.presentation.calendarBounds
import org.example.project.features.reports.presentation.windowFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Covers `calendarBounds` per granularity plus the live-period branch of `windowFor` — both were
 * previously exercised only indirectly (or not at all) while `CustomGranularityTest` covered just the
 * CUSTOM edge cases. This is the regression net for the `calendarBounds` extraction: [windowFor] is
 * now defined in terms of it, so every branch here doubles as proof the extraction didn't change
 * observable behaviour.
 */
class ReportsGranularityWindowTest {
    // A Thursday, comfortably mid-week, in a 31-day month, in a non-leap year.
    private val marchAnchor = LocalDate(2026, 3, 12)

    @Test
    fun calendarBounds_day_isTheAnchorItself() {
        assertEquals(marchAnchor to marchAnchor, ReportsGranularity.DAY.calendarBounds(marchAnchor))
    }

    @Test
    fun calendarBounds_week_snapsMidWeekAnchorToMonday() {
        // 2026-03-12 is a Thursday; ISO week is Mon 2026-03-09 .. Sun 2026-03-15.
        assertEquals(
            LocalDate(2026, 3, 9) to LocalDate(2026, 3, 15),
            ReportsGranularity.WEEK.calendarBounds(marchAnchor),
        )
    }

    @Test
    fun calendarBounds_week_wrapsASundayAnchorBackToItsOwnMonday() {
        // 2026-03-15 is a Sunday — the wraparound case: the week must not roll into next week.
        val sunday = LocalDate(2026, 3, 15)
        assertEquals(
            LocalDate(2026, 3, 9) to LocalDate(2026, 3, 15),
            ReportsGranularity.WEEK.calendarBounds(sunday),
        )
    }

    @Test
    fun calendarBounds_month_last31DayMonth() {
        assertEquals(
            LocalDate(2026, 3, 1) to LocalDate(2026, 3, 31),
            ReportsGranularity.MONTH.calendarBounds(marchAnchor),
        )
    }

    @Test
    fun calendarBounds_month_last30DayMonth() {
        assertEquals(
            LocalDate(2026, 4, 1) to LocalDate(2026, 4, 30),
            ReportsGranularity.MONTH.calendarBounds(LocalDate(2026, 4, 15)),
        )
    }

    @Test
    fun calendarBounds_month_februaryInALeapYear() {
        // 2028 is a leap year: February must resolve to the 29th, not the 28th.
        assertEquals(
            LocalDate(2028, 2, 1) to LocalDate(2028, 2, 29),
            ReportsGranularity.MONTH.calendarBounds(LocalDate(2028, 2, 10)),
        )
    }

    @Test
    fun calendarBounds_year() {
        assertEquals(
            LocalDate(2026, 1, 1) to LocalDate(2026, 12, 31),
            ReportsGranularity.YEAR.calendarBounds(marchAnchor),
        )
    }

    @Test
    fun calendarBounds_custom_throws() {
        assertFailsWith<IllegalStateException> {
            ReportsGranularity.CUSTOM.calendarBounds(marchAnchor)
        }
        Unit
    }

    // Live-period cases: `now` falls inside the anchored period, so the end bound must be `now`
    // itself, not the calendar end — this is what proves the calendarBounds extraction left the
    // live branch of windowFor unchanged.

    @Test
    fun windowFor_day_livePeriod_endsAtNow() {
        val now = LocalDateTime(2026, 3, 12, 14, 30, 0)
        val (_, end) = ReportsGranularity.DAY.windowFor(marchAnchor, now)
        assertEquals(now.toString(), end)
    }

    @Test
    fun windowFor_week_livePeriod_endsAtNow() {
        val now = LocalDateTime(2026, 3, 14, 9, 0, 0) // Saturday, same ISO week as marchAnchor
        val (_, end) = ReportsGranularity.WEEK.windowFor(marchAnchor, now)
        assertEquals(now.toString(), end)
    }

    @Test
    fun windowFor_month_livePeriod_endsAtNow() {
        val now = LocalDateTime(2026, 3, 30, 23, 0, 0)
        val (_, end) = ReportsGranularity.MONTH.windowFor(marchAnchor, now)
        assertEquals(now.toString(), end)
    }

    @Test
    fun windowFor_year_livePeriod_endsAtNow() {
        val now = LocalDateTime(2026, 12, 1, 0, 0, 0)
        val (_, end) = ReportsGranularity.YEAR.windowFor(marchAnchor, now)
        assertEquals(now.toString(), end)
    }
}
