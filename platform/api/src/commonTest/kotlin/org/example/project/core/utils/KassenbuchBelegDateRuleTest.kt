package org.example.project.core.utils

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Beleg-date rule the server guard and both entry forms share. */
class KassenbuchBelegDateRuleTest {
    private val today = LocalDate(2026, 9, 21)

    @Test
    fun todayAndYesterdayAreAcceptable() {
        assertNull(KassenbuchBelegDateRule.check(today, today, 31))
        assertNull(KassenbuchBelegDateRule.check(LocalDate(2026, 9, 20), today, 31))
    }

    @Test
    fun aFutureDateIsRefused() {
        assertEquals(KassenbuchBelegDateViolation.FUTURE, KassenbuchBelegDateRule.check(LocalDate(2026, 9, 22), today, 31))
    }

    @Test
    fun theLimitIsInclusive() {
        // 31 days back from 21.09. is 21.08.
        assertNull(KassenbuchBelegDateRule.check(LocalDate(2026, 8, 21), today, 31))
        assertEquals(KassenbuchBelegDateViolation.TOO_OLD, KassenbuchBelegDateRule.check(LocalDate(2026, 8, 20), today, 31))
    }

    @Test
    fun aMistypedYearIsRefused() {
        assertEquals(KassenbuchBelegDateViolation.TOO_OLD, KassenbuchBelegDateRule.check(LocalDate(1999, 1, 1), today, 31))
    }

    @Test
    fun anAbsentOrImpossibleLimitFallsBackToTheDefault() {
        assertEquals(31, KassenbuchBelegDateRule.effectiveMaxAgeDays(null))
        assertEquals(31, KassenbuchBelegDateRule.effectiveMaxAgeDays(0))
        assertEquals(31, KassenbuchBelegDateRule.effectiveMaxAgeDays(10_000))
        assertEquals(10, KassenbuchBelegDateRule.effectiveMaxAgeDays(10))
    }
}
