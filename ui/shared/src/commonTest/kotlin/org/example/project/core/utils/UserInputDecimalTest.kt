package org.example.project.core.utils

import org.example.project.money.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Typed money (E2E-2026-09-18 #6): "150,00" must mean 150,00, never zero. */
class UserInputDecimalTest {
    @Test
    fun germanCommaIsTheDecimalSeparator() {
        assertEquals(Decimal.parse("150.00"), "150,00".toUserInputDecimalOrNull())
    }

    @Test
    fun dotIsAcceptedToo() {
        assertEquals(Decimal.parse("150.00"), "150.00".toUserInputDecimalOrNull())
    }

    @Test
    fun surroundingWhitespaceAndASignAreFine() {
        assertEquals(Decimal.parse("-19.11"), "  -19,11 ".toUserInputDecimalOrNull())
    }

    @Test
    fun thousandsSeparatorsAreRejectedRatherThanGuessed() {
        // "1.234,56" is ambiguous input; refusing is safer than booking 1.23456 or 123456.
        assertNull("1.234,56".toUserInputDecimalOrNull())
    }

    @Test
    fun blankAndGarbageAreNull() {
        assertNull("".toUserInputDecimalOrNull())
        assertNull("12a".toUserInputDecimalOrNull())
    }

    @Test
    fun aTrailingSeparatorIsAnAmountHalfTyped() {
        // F65: "3," on the way to "3,50" is 3, never "no amount".
        assertEquals(Decimal.parse("3"), "3,".toUserInputDecimalOrNull())
        assertEquals(Decimal.parse("3"), "3.".toUserInputDecimalOrNull())
        assertEquals("3", "3,".toCanonicalMoneyOrNull())
    }

    @Test
    fun aSeparatorWithoutDigitsIsStillNull() {
        assertNull(",".toUserInputDecimalOrNull())
        assertNull("-,".toUserInputDecimalOrNull())
        assertNull("3,,".toUserInputDecimalOrNull())
    }

    @Test
    fun canonicalWireFormDelegates() {
        assertEquals("150.00", "150,00".toCanonicalMoneyOrNull())
    }
}
