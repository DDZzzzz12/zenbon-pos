package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyAverageTest {
    @Test
    fun sevenBillsAndTwoFiftyOfTipsAverageToThirtySixCents() {
        assertEquals("0,36 €", averageMoneyPerCount("2.50", 7, "EUR"))
    }

    @Test
    fun noBillsIsZeroNotADivisionByZero() {
        assertEquals("0,00 €", averageMoneyPerCount("2.50", 0, "EUR"))
    }

    @Test
    fun roundsHalfUpToTwoDecimals() {
        // 1.00 / 8 = 0.125 -> 0.13 (HALF_UP), the policy the server's period comparison uses.
        assertEquals("0,13 €", averageMoneyPerCount("1.00", 8, "EUR"))
    }

    @Test
    fun aMalformedTotalDegradesInsteadOfThrowing() {
        // Must not throw; the placeholder is whatever formatMoneyForDisplay renders for the bad input.
        averageMoneyPerCount("not-a-number", 3, "EUR")
    }
}
