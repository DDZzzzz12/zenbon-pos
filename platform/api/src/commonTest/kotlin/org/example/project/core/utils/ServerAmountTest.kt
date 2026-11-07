package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `formatGerman` on the server produces `DecimalFormat("#,##0.00")` with German symbols: dot for
 * grouping, comma for the decimal point, always two fraction digits. These pin the shapes it can
 * actually emit, plus the pass-through for the canonical decimal the endpoint sends now.
 */
class ServerAmountTest {
    @Test
    fun germanGroupingAndDecimalSeparatorsBecomeANeutralDecimal() {
        assertEquals("1234.56", normalizeServerFormattedAmount("1.234,56"))
        assertEquals("0.00", normalizeServerFormattedAmount("0,00"))
        assertEquals("500.00", normalizeServerFormattedAmount("500,00"))
        // Two grouping separators — the shape that made a naive single replace() wrong.
        assertEquals("1234567.89", normalizeServerFormattedAmount("1.234.567,89"))
    }

    @Test
    fun negativeBalancesKeepTheirSign() {
        assertEquals("-1234.56", normalizeServerFormattedAmount("-1.234,56"))
    }

    @Test
    fun anAlreadyNeutralDecimalIsUnchanged() {
        assertEquals("500.0000", normalizeServerFormattedAmount("500.0000"))
        assertEquals("0.00", normalizeServerFormattedAmount("0.00"))
        assertEquals("-7", normalizeServerFormattedAmount("-7"))
    }

    @Test
    fun surroundingWhitespaceIsDropped() {
        assertEquals("1234.56", normalizeServerFormattedAmount("  1.234,56\n"))
    }
}
