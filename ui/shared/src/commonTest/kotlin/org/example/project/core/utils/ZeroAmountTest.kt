package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZeroAmountTest {
    @Test
    fun zeroDisplaysAreZero() {
        assertTrue(isZeroAmountDisplay("0,00 €"))
        assertTrue(isZeroAmountDisplay("0"))
    }

    @Test
    fun nonZeroDisplaysAreNot() {
        assertFalse(isZeroAmountDisplay("10,00 €"))
        assertFalse(isZeroAmountDisplay("0,01 €"))
        assertFalse(isZeroAmountDisplay("1.000,00 €"))
        assertFalse(isZeroAmountDisplay("-5,00 €"))
    }

    @Test
    fun signedZeroAndPlaceholderAreZero() {
        assertTrue(isZeroAmountDisplay("-0,00 €"))
        assertTrue(isZeroAmountDisplay("—"))
    }
}
