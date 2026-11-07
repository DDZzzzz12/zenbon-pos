package org.example.project.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The blur-time normalisation behind [PosTextFieldType.Price].
 *
 * It lived inside an `onFocusChanged` lambda, which is how it silently truncated scale-4 prices
 * for so long. It is a top-level function now precisely so these cases are reachable.
 */
class PriceInputNormalizationTest {
    @Test
    fun padsToTwoDecimals() {
        // Existing behaviour that ~17 two-decimal call sites rely on: blur settles the field.
        assertEquals("16.90", normalizePriceInput("16.9"))
        assertEquals("16.00", normalizePriceInput("16"))
    }

    @Test
    fun leavesATwoDecimalValueAlone() {
        assertEquals("16.90", normalizePriceInput("16.90"))
        assertEquals("0.00", normalizePriceInput("0"))
    }

    @Test
    fun keepsPrecisionBeyondTwoDecimals() {
        // The defect: the old truncating parse pinned the scale at 2, so opening the
        // product dialog on a DECIMAL(19,4) price rewrote "16.895" to "16.89" and saving
        // persisted the loss — half a cent gone without the user touching the price.
        assertEquals("16.895", normalizePriceInput("16.895"))
        assertEquals("16.8950", normalizePriceInput("16.8950"))
        assertEquals("0.0001", normalizePriceInput("0.0001"))
    }

    @Test
    fun acceptsTheGermanDecimalComma() {
        // Decimal.parse is strict ASCII "[+-]?digits[.digits]" and would reject "16,90"
        // outright; the separator is normalised first, as PosEditItemPanel does.
        assertEquals("16.90", normalizePriceInput("16,90"))
        assertEquals("16.895", normalizePriceInput("16,895"))
    }

    @Test
    fun handlesNegativesAndSurroundingWhitespace() {
        assertEquals("-16.90", normalizePriceInput("-16.9"))
        assertEquals("16.90", normalizePriceInput("  16,90  "))
    }

    @Test
    fun toleratesACurrencySymbol() {
        // Preserved from the old parse path this replaces, which stripped anything that
        // was not a digit, separator or sign. Desktop users can paste a formatted amount.
        assertEquals("12.50", normalizePriceInput("€12.50"))
        assertEquals("12.50", normalizePriceInput("12,50 €"))
    }

    @Test
    fun acceptsALeadingDecimalPoint() {
        // Keypad shorthand for half a euro. The old route read it as 0.50; Decimal.parse
        // demands an integer part, so one is supplied rather than rejecting the value.
        assertEquals("0.50", normalizePriceInput(".5"))
        assertEquals("0.50", normalizePriceInput(",5"))
        assertEquals("-0.50", normalizePriceInput("-.5"))
        assertEquals("0.0500", normalizePriceInput(".0500"))
    }

    @Test
    fun rejectsAHalfTypedValue() {
        // Was far worse than an error: the old route turned "16." into 160 minor units, i.e. "1.60".
        assertNull(normalizePriceInput("16."))
        assertNull(normalizePriceInput("."))
        assertNull(normalizePriceInput("-"))
    }

    @Test
    fun rejectsNonNumericInput() {
        assertNull(normalizePriceInput("abc"))
        // A digit next to letters stays rejected rather than being salvaged to "12.00".
        assertNull(normalizePriceInput("12abc"))
    }

    @Test
    fun rejectsBlankInput() {
        // The field treats blank as "nothing to normalise" before ever calling this; returning
        // null keeps the function honest for any other caller.
        assertNull(normalizePriceInput(""))
        assertNull(normalizePriceInput("   "))
    }

    @Test
    fun rejectsAValueOutsideTheDecimalEnvelope() {
        // Must return, never throw: this runs inside a Compose focus callback.
        assertNull(normalizePriceInput("1" + "0".repeat(18)))
        assertNull(normalizePriceInput("1.0000000000000"))
    }
}
