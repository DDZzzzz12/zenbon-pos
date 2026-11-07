package org.example.project.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoneyTest {
    private fun eur(text: String) = Money(Decimal.parse(text), Currency.EUR)

    @Test
    fun validatesCurrencyCodes() {
        assertEquals("EUR", Currency.parse("EUR").code)
        assertFailsWith<CurrencyFormatException> { Currency.parse("EU") }
        assertFailsWith<CurrencyFormatException> { Currency.parse("EURO") }
        assertFailsWith<CurrencyFormatException> { Currency.parse("eur") }
        assertFailsWith<CurrencyFormatException> { Currency.parse("E1R") }
    }

    @Test
    fun exposesCommonConstants() {
        assertEquals("EUR", Currency.EUR.code)
        assertEquals("USD", Currency.USD.code)
        assertEquals("CHF", Currency.CHF.code)
    }

    @Test
    fun addsAndSubtractsSameCurrency() {
        assertEquals(eur("3.00"), eur("1.00") + eur("2.00"))
        assertEquals(eur("1.00"), eur("3.00") - eur("2.00"))
    }

    @Test
    fun rejectsCrossCurrencyArithmetic() {
        val usd = Money(Decimal.parse("2.00"), Currency.USD)
        assertFailsWith<CurrencyMismatchException> { eur("1.00") + usd }
        assertFailsWith<CurrencyMismatchException> { eur("1.00") - usd }
        assertFailsWith<CurrencyMismatchException> { eur("1.00").compareTo(usd) }
    }

    @Test
    fun multipliesByQuantity() {
        assertEquals(eur("6.00"), eur("2.00") * 3)
        assertEquals(eur("-6.00"), eur("2.00") * -3)
        assertEquals(eur("0.00"), eur("2.00") * 0)
    }

    @Test
    fun supportsNegativeAmountsForStornoAndDiscounts() {
        val negative = eur("-5.00")
        assertTrue(negative.isNegative)
        assertEquals(eur("5.00"), negative.abs())
        assertEquals(eur("5.00"), -negative)
    }

    @Test
    fun reportsZero() {
        assertTrue(eur("0.00").isZero)
        assertTrue(!eur("0.01").isZero)
    }

    @Test
    fun equalityIncludesCurrencyAndIsValueBasedOnAmount() {
        assertEquals(eur("16.90"), eur("16.9000"))
        assertTrue(eur("16.90") != Money(Decimal.parse("16.90"), Currency.USD))
    }

    @Test
    fun dividesAndSetsScale() {
        assertEquals(eur("3.33"), eur("10.00").divide(Decimal.parse("3"), 2, MoneyRounding.HALF_UP))
        assertEquals(eur("10.0000"), eur("10.00").setScale(4, MoneyRounding.HALF_UP))
    }

    @Test
    fun timesWithFactorRoundsAtGivenScale() {
        assertEquals(eur("2.50"), eur("2.00").times(Decimal.parse("1.25"), 2, MoneyRounding.HALF_UP))
    }

    @Test
    fun printsAmountOnly() {
        assertEquals("16.90", eur("16.90").toPlainString())
    }
}
