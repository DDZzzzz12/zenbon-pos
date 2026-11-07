package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Editors seed and send decimals (QA admin21 NOTE-e21 / NOTE-d21): a server value is shown without
 * its DB-scale padding, and a typed value goes out as the canonical dot decimal whichever separator
 * the operator used.
 */
class EditableDecimalTest {
    @Test
    fun dbScalePaddingIsDroppedDownToTwoDecimals() {
        assertEquals("1.50", "1.5000".toEditableDecimal())
        assertEquals("12.90", "12.9000".toEditableDecimal())
        assertEquals("0.00", "0.0000".toEditableDecimal())
        assertEquals("-2.00", "-2.0000".toEditableDecimal())
    }

    @Test
    fun significantDigitsAreNeverDropped() {
        assertEquals("16.895", "16.8950".toEditableDecimal())
        assertEquals("0.0001", "0.0001".toEditableDecimal())
    }

    @Test
    fun shortValuesArePaddedToTheMinimumScale() {
        assertEquals("19.00", "19".toEditableDecimal())
        assertEquals("16.90", "16.9".toEditableDecimal())
        assertEquals("19", "19.0000".toEditableDecimal(minScale = 0))
        assertEquals("7.5", "7.5000".toEditableDecimal(minScale = 0))
    }

    @Test
    fun nonDecimalInputIsReturnedUnchanged() {
        assertEquals("", "".toEditableDecimal())
        assertEquals("abc", "abc".toEditableDecimal())
    }

    @Test
    fun aTypedPriceGoesOutCanonicalWithEitherSeparator() {
        assertEquals("12.90", "12,90".toCanonicalPriceOrNull())
        assertEquals("12.90", " 12.90 ".toCanonicalPriceOrNull())
        assertEquals("0", "0".toCanonicalPriceOrNull())
    }

    @Test
    fun aNegativeOrUnreadablePriceIsRefused() {
        assertNull("-1,00".toCanonicalPriceOrNull())
        assertNull("12,9x".toCanonicalPriceOrNull())
        assertNull("1.234,50".toCanonicalPriceOrNull())
        assertNull("".toCanonicalPriceOrNull())
    }
}
