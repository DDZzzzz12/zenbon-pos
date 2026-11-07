package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals

/** F63: printed amounts read the German way — `11,50`, not `11.50`. */
class PrintedAmountTest {
    @Test
    fun `a wire amount prints with a decimal comma and two decimals`() {
        assertEquals("11,50", printedAmount("11.5"))
        assertEquals("6,00", printedAmount("6.0000"))
        assertEquals("0,39", printedAmount("0.39"))
    }

    @Test
    fun `thousands are grouped with a dot, as the money module's German format does`() {
        assertEquals("1.234,50", printedAmount("1234.50"))
        assertEquals("-12.345,68", printedAmount("-12345.675"))
    }

    @Test
    fun `a negative amount keeps its sign`() {
        assertEquals("-0,50", printedAmount("-0.5000"))
    }

    @Test
    fun `a value that is not a decimal is printed unchanged`() {
        assertEquals("n/a", printedAmount("n/a"))
    }
}
