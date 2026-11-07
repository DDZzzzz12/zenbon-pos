package org.example.project.admin.core.util

import kotlin.test.Test
import kotlin.test.assertEquals

class DateTimeFormatTest {
    @Test
    fun formatsIsoLocalDateTime() {
        assertEquals("01.08.2026 14:05", formatDashboardDateTime("2026-08-01T14:05:00"))
    }

    @Test
    fun returnsInputOnInvalidString() {
        assertEquals("not-a-date", formatDashboardDateTime("not-a-date"))
    }
}
