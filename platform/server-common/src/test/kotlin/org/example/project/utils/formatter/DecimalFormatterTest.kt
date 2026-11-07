package org.example.project.utils.formatter

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `toDecimalString()` pins scale at 2 for money that is displayed as money. EN 16931 unit prices
 * (BT-146) and quantities carry more, so a second formatter is needed that keeps what the column
 * holds. Sending a DECIMAL(19,4) through the scale-2 formatter silently rounds it.
 */
class DecimalFormatterTest {
    @Test
    fun toDecimalStringStillPinsScaleTwo() {
        assertEquals("19.00", BigDecimal("19").toDecimalString())
        assertEquals("1.23", BigDecimal("1.2349").toDecimalString())
    }

    @Test
    fun toExactDecimalStringKeepsTheScaleItWasGiven() {
        assertEquals("1.2349", BigDecimal("1.2349").toExactDecimalString())
        assertEquals("19.00", BigDecimal("19.00").toExactDecimalString())
        assertEquals("19", BigDecimal("19").toExactDecimalString())
    }

    // Plain string, never scientific notation — BigDecimal.toString() switches to E-notation for
    // some values and that would be sent over the wire verbatim.
    @Test
    fun toExactDecimalStringNeverUsesScientificNotation() {
        assertEquals("0.0000001", BigDecimal("1E-7").toExactDecimalString())
    }

    @Test
    fun toMoneyMessageStringDropsStoredZerosButKeepsRealSubCentDigits() {
        assertEquals("23.80", BigDecimal("23.8000").toMoneyMessageString())
        assertEquals("100.00", BigDecimal("100").toMoneyMessageString())
        assertEquals("-12.50", BigDecimal("-12.5000").toMoneyMessageString())
        // A sub-cent mismatch must stay visible instead of rounding to the other figure.
        assertEquals("23.8049", BigDecimal("23.8049").toMoneyMessageString())
    }
}
