package org.example.project.admin.ui

import org.example.project.ui.components.chart.tradedHourRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TradedHoursTest {
    @Test
    fun trimsLeadingAndTrailingZeroHours() {
        val map = (0..23).associateWith { if (it in 11..14) "100.00" else "0.00" }
        assertEquals(11..14, tradedHourRange(map))
    }

    @Test
    fun keepsInteriorZeroHours() {
        val map = mapOf(11 to "100.00", 12 to "0.00", 13 to "50.00")
        assertEquals(11..13, tradedHourRange(map))
    }

    @Test
    fun allZeroReturnsNull() {
        assertNull(tradedHourRange((0..23).associateWith { "0.00" }))
    }

    @Test
    fun emptyReturnsNull() {
        assertNull(tradedHourRange(emptyMap()))
    }

    @Test
    fun singleTradedHour() {
        assertEquals(17..17, tradedHourRange(mapOf(17 to "42.00")))
    }
}
