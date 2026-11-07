package org.example.project.core.presentation.components.picker

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Material's DatePicker counts UTC days. A date field holding the 1st must open on that month with
 * the 1st selected, whatever the browser's zone (it used to open on the previous month east of UTC).
 */
class PickerMillisTest {
    @Test
    fun `a date is the UTC start of its day`() {
        // 2026-03-01T00:00Z
        assertEquals(1_772_323_200_000L, LocalDate(2026, 3, 1).toPickerMillis())
    }

    @Test
    fun `millis round trip to the same date, month boundaries included`() {
        listOf(LocalDate(2026, 3, 1), LocalDate(2026, 2, 28), LocalDate(2024, 2, 29), LocalDate(2026, 12, 31))
            .forEach { date -> assertEquals(date, pickerMillisToDate(date.toPickerMillis())) }
    }
}
