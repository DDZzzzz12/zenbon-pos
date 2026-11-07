package org.example.project.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The blur-time normalisation behind [PosTextFieldType.Percentage].
 *
 * It lived inside an `onFocusChanged` lambda that round-tripped through the old truncating parse —
 * the same defect the price path carried, so a rate of "19.005" was pinned to two decimals and
 * TRUNCATED to "19". It is a top-level function now precisely so these cases are reachable.
 *
 * A percentage is not money: unlike a price it keeps the exact scale it was typed or seeded with,
 * so a whole VAT rate stays "19" (never padded to "19.00") while a fractional discount keeps every
 * digit it has. That is the only behavioural difference from [normalizePriceInput].
 */
class PercentInputNormalizationTest {
    @Test
    fun keepsAWholeRateWhole() {
        // The min-scale decision: VAT rates are whole numbers, so blur must not pad them. This is
        // the opposite of a price, which settles to "19.00".
        assertEquals("19", normalizePercentInput("19"))
        assertEquals("7", normalizePercentInput("7"))
        assertEquals("0", normalizePercentInput("0"))
    }

    @Test
    fun keepsTheScaleThatWasTyped() {
        // Neither padded nor trimmed: the field reflects exactly the precision the value carries.
        assertEquals("19.5", normalizePercentInput("19.5"))
        assertEquals("19.50", normalizePercentInput("19.50"))
    }

    @Test
    fun keepsPrecisionBeyondTwoDecimals() {
        // The defect: the old truncating parse pinned the scale at 2, so "19.005" became
        // "19" — the whole fractional part gone. A fractional discount lost its third decimal the
        // same way.
        assertEquals("19.005", normalizePercentInput("19.005"))
        assertEquals("7.125", normalizePercentInput("7.125"))
        assertEquals("0.0001", normalizePercentInput("0.0001"))
    }

    @Test
    fun acceptsTheGermanDecimalComma() {
        // Decimal.parse is strict ASCII "[+-]?digits[.digits]" and would reject "19,5" outright;
        // the separator is normalised first, as the price path does.
        assertEquals("19.5", normalizePercentInput("19,5"))
        assertEquals("19.005", normalizePercentInput("19,005"))
    }

    @Test
    fun handlesNegativesAndSurroundingWhitespace() {
        // The old branch dropped the sign with abs(), silently turning a typo into a positive
        // rate. The value is preserved now and left for the caller to accept or reject.
        assertEquals("-5", normalizePercentInput("-5"))
        assertEquals("19.5", normalizePercentInput("  19,5  "))
    }

    @Test
    fun toleratesAPercentSign() {
        // A percent field is exactly where a "%" gets typed or pasted; it is stripped like any
        // other non-numeric symbol, as the old parse did.
        assertEquals("19", normalizePercentInput("19%"))
        assertEquals("19.5", normalizePercentInput("19,5 %"))
    }

    @Test
    fun acceptsALeadingDecimalPoint() {
        // Keypad shorthand for half a percent. Decimal.parse demands an integer part, so one is
        // supplied rather than rejecting the value.
        assertEquals("0.5", normalizePercentInput(".5"))
        assertEquals("0.5", normalizePercentInput(",5"))
        assertEquals("-0.5", normalizePercentInput("-.5"))
    }

    @Test
    fun rejectsAHalfTypedValue() {
        assertNull(normalizePercentInput("19."))
        assertNull(normalizePercentInput("."))
        assertNull(normalizePercentInput("-"))
    }

    @Test
    fun rejectsNonNumericInput() {
        assertNull(normalizePercentInput("abc"))
        // A digit next to letters stays rejected rather than being salvaged to "12".
        assertNull(normalizePercentInput("12abc"))
    }

    @Test
    fun rejectsBlankInput() {
        // The field treats blank as "nothing to normalise" before ever calling this; returning
        // null keeps the function honest for any other caller.
        assertNull(normalizePercentInput(""))
        assertNull(normalizePercentInput("   "))
    }

    @Test
    fun rejectsAValueOutsideTheDecimalEnvelope() {
        // Must return, never throw: this runs inside a Compose focus callback.
        assertNull(normalizePercentInput("1" + "0".repeat(18)))
        assertNull(normalizePercentInput("1.0000000000000"))
    }
}
