package org.example.project.features.reports.presentation

import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeriodPickerLogicTest {
    // 1 Aug 2026 is a Saturday (see monthGrid tests below for the weekday math).
    private val today = LocalDate(2026, Month.AUGUST, 1)

    @Test
    fun monthGrid_august2026_startsMondayPadsToWholeWeeks() {
        // 1 Aug 2026 is a Saturday: dayOfWeek.ordinal == 5 (Mon = 0). 5 lead nulls + 31 days = 36 cells.
        val grid = monthGrid(2026, Month.AUGUST)
        assertEquals(42, grid.size, "36 cells must be padded to a whole number of weeks")
        assertNull(grid[0])
        assertNull(grid[4])
        assertEquals(LocalDate(2026, Month.AUGUST, 1), grid[5])
        assertEquals(LocalDate(2026, Month.AUGUST, 31), grid[35])
        assertNull(grid[36])
        assertNull(grid[41])
    }

    @Test
    fun monthGrid_february2024_leapYear() {
        // 1 Feb 2024 is a Thursday: ordinal == 3. 3 nulls + 29 days = 32 cells -> pad to 35.
        val grid = monthGrid(2024, Month.FEBRUARY)
        assertEquals(35, grid.size)
        assertEquals(LocalDate(2024, Month.FEBRUARY, 1), grid[3])
        assertEquals(LocalDate(2024, Month.FEBRUARY, 29), grid[31])
    }

    @Test
    fun monthGrid_february2023_nonLeapYear() {
        // 1 Feb 2023 is a Wednesday: ordinal == 2. 2 nulls + 28 days = 30 cells -> pad to 35.
        val grid = monthGrid(2023, Month.FEBRUARY)
        assertEquals(35, grid.size)
        assertEquals(LocalDate(2023, Month.FEBRUARY, 1), grid[2])
        assertEquals(LocalDate(2023, Month.FEBRUARY, 28), grid[29])
    }

    @Test
    fun isFutureDay_todayItselfIsNotFuture() {
        assertFalse(isFutureDay(LocalDate(2026, Month.AUGUST, 1), today))
        assertTrue(isFutureDay(LocalDate(2026, Month.AUGUST, 2), today))
    }

    @Test
    fun isFutureWeek_sundayOfCurrentWeekSelectableNextMondayFuture() {
        // Sunday 2 Aug 2026 still belongs to the ISO week starting Mon 27 Jul 2026.
        assertFalse(isFutureWeek(LocalDate(2026, Month.AUGUST, 2), today))
        // Monday 3 Aug 2026 starts a new ISO week -> future.
        assertTrue(isFutureWeek(LocalDate(2026, Month.AUGUST, 3), today))
    }

    @Test
    fun isFutureMonth_onlyLaterMonthsInCurrentYear() {
        assertFalse(isFutureMonth(2026, Month.AUGUST, today), "current month is not future")
        assertTrue(isFutureMonth(2026, Month.SEPTEMBER, today), "later month in current year is future")
        assertFalse(isFutureMonth(2026, Month.JULY, today), "earlier month in current year is not future")
        assertFalse(isFutureMonth(2025, Month.DECEMBER, today), "past year is not future")
        assertTrue(isFutureMonth(2027, Month.JANUARY, today), "future year is future")
    }

    @Test
    fun isFutureYear_boundary() {
        assertFalse(isFutureYear(2026, today), "current year is not future")
        assertTrue(isFutureYear(2027, today), "next year is future")
    }

    @Test
    fun decadeFor_boundaries() {
        assertEquals(2020 to 2029, decadeFor(2026))
        assertEquals(2010 to 2019, decadeFor(2019))
        assertEquals(2000 to 2009, decadeFor(2000))
        assertEquals(1990 to 1999, decadeFor(1999))
    }
}
