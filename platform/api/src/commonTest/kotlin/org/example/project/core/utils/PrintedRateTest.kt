package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals

/** F63: printed rates read the German way — `19 %`, `10,7 %`, not `19.00%`. */
class PrintedRateTest {
    @Test
    fun `a percent-unit rate prints without trailing zeros and with a spaced percent sign`() {
        assertEquals("19 %", printedRate("19.00"))
        assertEquals("7 %", printedRate("7"))
        assertEquals("10,7 %", printedRate("10.70"))
        assertEquals("0 %", printedRate("0.00"))
    }

    @Test
    fun `a fractional rate prints the same form`() {
        assertEquals("19 %", printedRateOfFraction("0.19"))
        assertEquals("7 %", printedRateOfFraction("0.07"))
        assertEquals("10,7 %", printedRateOfFraction("0.107"))
    }

    @Test
    fun `a value that is not a decimal is printed as given`() {
        assertEquals("n/a %", printedRate("n/a"))
    }
}
