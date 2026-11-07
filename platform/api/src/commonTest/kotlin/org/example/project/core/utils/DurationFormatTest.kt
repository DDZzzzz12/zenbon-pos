package org.example.project.core.utils

import org.example.project.core.domain.enums.setup.Language
import kotlin.test.Test
import kotlin.test.assertEquals

class DurationFormatTest {
    @Test
    fun halfHourIsNotTruncated() {
        assertEquals("27,5 Std.", formatHours(1650, Language.GERMAN))
        assertEquals("27.5 h", formatHours(1650, Language.ENGLISH))
        assertEquals("27.5 小时", formatHours(1650, Language.CHINESE))
    }

    @Test
    fun fractionIsRoundedNotTruncated() {
        assertEquals("7,67 Std.", formatHours(460, Language.GERMAN))
        assertEquals("39,67 Std.", formatHours(2380, Language.GERMAN))
        assertEquals("0,98 Std.", formatHours(59, Language.GERMAN))
    }

    @Test
    fun negativeMinutesKeepOneSign() {
        assertEquals("-1,5 Std.", formatHours(-90, Language.GERMAN))
    }

    @Test
    fun wholeHoursDropTheFraction() {
        assertEquals("30 Std.", formatHours(1800, Language.GERMAN))
    }

    @Test
    fun quarterHoursKeepTwoDecimals() {
        assertEquals("0,25 Std.", formatHours(15, Language.GERMAN))
    }

    @Test
    fun zero() {
        assertEquals("0 Std.", formatHours(0, Language.GERMAN))
    }

    @Test
    fun serverHoursStringBecomesMinutes() {
        assertEquals(1800, "30.00".hoursToMinutesOrNull())
        assertEquals(1650, "27.5".hoursToMinutesOrNull())
        assertEquals(2235, "37.25".hoursToMinutesOrNull())
    }

    @Test
    fun nonDecimalHoursAreNull() {
        assertEquals(null, "abc".hoursToMinutesOrNull())
        assertEquals(null, "".hoursToMinutesOrNull())
    }
}
