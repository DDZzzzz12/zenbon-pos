package org.example.project.features.statistics

import kotlinx.datetime.LocalDateTime
import org.example.project.features.statistics.domain.ComparisonWindowResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The like-for-like baseline is pure date arithmetic, so it is tested without a database.
 *
 * DAY and WEEK are fixed-length: a 7-day shift is the baseline either way. `complete` only branches
 * MONTH and YEAR, where a whole past period compares to the whole previous one while the current
 * partial period keeps the same-elapsed rule.
 */
class StatisticsComparisonWindowTest {
    private fun dt(s: String) = LocalDateTime.parse(s)

    @Test
    fun day_comparesAgainstTheSameWeekdayOneWeekEarlier() {
        // Wed 10 June 2026 -> Wed 3 June, carrying the intraday end time.
        val w =
            ComparisonWindowResolver.resolve(
                dt("2026-06-10T00:00:00"),
                dt("2026-06-10T14:32:00"),
                "DAY",
                complete = false,
            )
        assertEquals(dt("2026-06-03T00:00:00"), w?.start)
        assertEquals(dt("2026-06-03T14:32:00"), w?.end)
    }

    @Test
    fun day_completePastDayAlsoComparesToSameWeekdayLastWeek() {
        val w =
            ComparisonWindowResolver.resolve(
                dt("2026-06-09T00:00:00"),
                dt("2026-06-10T00:00:00"),
                "DAY",
                complete = true,
            )
        assertEquals(dt("2026-06-02T00:00:00"), w?.start)
        assertEquals(dt("2026-06-03T00:00:00"), w?.end)
    }

    @Test
    fun week_partialComparesSameElapsedDaysOfThePreviousWeek() {
        val w =
            ComparisonWindowResolver.resolve(
                dt("2026-06-08T00:00:00"),
                dt("2026-06-11T00:00:00"),
                "WEEK",
                complete = false,
            )
        assertEquals(dt("2026-06-01T00:00:00"), w?.start)
        assertEquals(dt("2026-06-04T00:00:00"), w?.end)
    }

    @Test
    fun week_completeComparesTheWholePreviousWeek() {
        val w =
            ComparisonWindowResolver.resolve(
                dt("2026-06-08T00:00:00"),
                dt("2026-06-15T00:00:00"),
                "WEEK",
                complete = true,
            )
        assertEquals(dt("2026-06-01T00:00:00"), w?.start)
        assertEquals(dt("2026-06-08T00:00:00"), w?.end)
    }

    @Test
    fun month_partialComparesEqualElapsedDaysCarryingTheEndTime() {
        // 1 June 00:00 -> 10 June 14:32 (month so far) vs 1 May 00:00 -> 10 May 14:32.
        val w =
            ComparisonWindowResolver.resolve(
                dt("2026-06-01T00:00:00"),
                dt("2026-06-10T14:32:00"),
                "MONTH",
                complete = false,
            )
        assertEquals(dt("2026-05-01T00:00:00"), w?.start)
        assertEquals(dt("2026-05-10T14:32:00"), w?.end)
    }

    @Test
    fun month_partialClampsWhenThePreviousMonthIsShorter() {
        // 1-31 March vs February (28 days in 2026): stop at 1 March, never roll forward.
        val w =
            ComparisonWindowResolver.resolve(
                dt("2026-03-01T00:00:00"),
                dt("2026-04-01T00:00:00"),
                "MONTH",
                complete = false,
            )
        assertEquals(dt("2026-02-01T00:00:00"), w?.start)
        assertEquals(dt("2026-03-01T00:00:00"), w?.end)
    }

    @Test
    fun month_completeComparesTheWholePreviousMonth() {
        val w =
            ComparisonWindowResolver.resolve(
                dt("2026-06-01T00:00:00"),
                dt("2026-07-01T00:00:00"),
                "MONTH",
                complete = true,
            )
        assertEquals(dt("2026-05-01T00:00:00"), w?.start)
        assertEquals(dt("2026-06-01T00:00:00"), w?.end)
    }

    @Test
    fun year_partialComparesPreviousYearSameElapsed() {
        val w =
            ComparisonWindowResolver.resolve(
                dt("2026-01-01T00:00:00"),
                dt("2026-06-10T00:00:00"),
                "YEAR",
                complete = false,
            )
        assertEquals(dt("2025-01-01T00:00:00"), w?.start)
        assertEquals(dt("2025-06-10T00:00:00"), w?.end)
    }

    @Test
    fun year_completeComparesTheWholePreviousYear() {
        val w =
            ComparisonWindowResolver.resolve(
                dt("2026-01-01T00:00:00"),
                dt("2027-01-01T00:00:00"),
                "YEAR",
                complete = true,
            )
        assertEquals(dt("2025-01-01T00:00:00"), w?.start)
        assertEquals(dt("2026-01-01T00:00:00"), w?.end)
    }

    @Test
    fun unknownGranularity_hasNoBaseline() {
        assertNull(
            ComparisonWindowResolver.resolve(
                dt("2026-06-01T00:00:00"),
                dt("2026-06-10T00:00:00"),
                "SINCE_LAST_ZBON",
                complete = false,
            ),
        )
    }
}
