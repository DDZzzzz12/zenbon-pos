package org.example.project.features.statistics

import org.example.project.features.statistics.domain.deltaPercent
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatisticsDeltaTest {
    @Test
    fun positiveChangeIsRoundedToTwoDecimalPlaces() {
        assertEquals("12.50", deltaPercent(BigDecimal("112.50"), BigDecimal("100.00")))
    }

    @Test
    fun negativeChangeKeepsItsSign() {
        assertEquals("-10.00", deltaPercent(BigDecimal("90.00"), BigDecimal("100.00")))
    }

    @Test
    fun zeroBaselineYieldsNullRatherThanAPercentage() {
        // A percentage against zero is undefined. Rendering +100 % against a period with no trade
        // is worse than rendering nothing at all.
        assertNull(deltaPercent(BigDecimal("50.00"), BigDecimal.ZERO))
    }

    @Test
    fun bothZeroYieldsNull() {
        assertNull(deltaPercent(BigDecimal.ZERO, BigDecimal.ZERO))
    }

    @Test
    fun zeroCurrentAgainstANonZeroBaselineIsMinusOneHundred() {
        assertEquals("-100.00", deltaPercent(BigDecimal.ZERO, BigDecimal("100.00")))
    }

    @Test
    fun roundingIsHalfUpAtTheSecondDecimal() {
        // 1/3 of the baseline = 33.333...%, must render 33.33 not 33.34 or 33.333
        assertEquals("33.33", deltaPercent(BigDecimal("400.00"), BigDecimal("300.00")))
    }
}
