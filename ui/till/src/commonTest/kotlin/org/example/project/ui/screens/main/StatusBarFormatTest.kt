package org.example.project.ui.screens.main

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class StatusBarFormatTest {
    private val now = LocalDateTime(2026, 10, 3, 9, 5, 7)

    @Test
    fun fullShowsDateWithYearThenTime() {
        assertEquals("03.10.2026  09:05:07", StatusBarFormat.stamp(now, compact = false))
    }

    @Test
    fun compactShowsDateOnlyNoTime() {
        assertEquals("03.10.2026", StatusBarFormat.stamp(now, compact = true))
    }

    @Test
    fun twoDigitDayAndMonthAreNotPadded() {
        assertEquals("25.12.2026", StatusBarFormat.stamp(LocalDateTime(2026, 12, 25, 0, 0, 0), compact = true))
    }
}
