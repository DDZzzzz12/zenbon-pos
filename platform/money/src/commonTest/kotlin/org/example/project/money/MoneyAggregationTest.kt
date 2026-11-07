package org.example.project.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyAggregationTest {
    private fun eur(text: String) = Money(Decimal.parse(text), Currency.EUR)

    @Test
    fun sumsMoney() {
        assertEquals(eur("6.00"), listOf(eur("1.00"), eur("2.00"), eur("3.00")).sum())
    }

    @Test
    fun sumUsesMaxScaleOfElements() {
        assertEquals(4, listOf(eur("1.00"), eur("2.0000")).sum().amount.scale)
    }

    @Test
    fun sumRejectsMixedCurrencies() {
        val mixed = listOf(eur("1.00"), Money(Decimal.parse("2.00"), Currency.USD))
        assertFailsWith<CurrencyMismatchException> { mixed.sum() }
    }

    @Test
    fun sumOfEmptyMoneyThrows() {
        assertFailsWith<MoneyEmptySumException> { emptyList<Money>().sum() }
    }

    @Test
    fun sumOrZeroHandlesEmpty() {
        assertEquals(Money(Decimal.ZERO, Currency.EUR), emptyList<Money>().sumOrZero(Currency.EUR))
    }

    @Test
    fun sumOrZeroRejectsAMismatchedElement() {
        val list = listOf(Money(Decimal.parse("1.00"), Currency.USD))
        assertFailsWith<CurrencyMismatchException> { list.sumOrZero(Currency.EUR) }
    }

    @Test
    fun sumOfMoneyMapsThenSums() {
        data class Line(
            val total: Money,
        )
        val lines = listOf(Line(eur("1.50")), Line(eur("2.50")))
        assertEquals(eur("4.00"), lines.sumOfMoney { it.total })
    }

    @Test
    fun sumOfMoneyOnEmptyThrows() {
        assertFailsWith<MoneyEmptySumException> { emptyList<Money>().sumOfMoney { it } }
    }

    @Test
    fun sumsDecimals() {
        assertEquals(
            "6.00",
            listOf(
                Decimal.parse("1.00"),
                Decimal.parse("2.00"),
                Decimal.parse("3.00"),
            ).sum().toPlainString(),
        )
    }

    @Test
    fun emptyDecimalSumIsZero() {
        assertEquals(Decimal.ZERO, emptyList<Decimal>().sum())
    }

    @Test
    fun largeCollectionSumsWithoutDrift() {
        // 1000 x 0.01 must be exactly 10.00, not 9.99 or 10.000000000000002
        val cents = List(1000) { eur("0.01") }
        assertEquals(eur("10.00"), cents.sum())
    }

    @Test
    fun negativeAndPositiveAmountsNetOut() {
        assertEquals(eur("0.00"), listOf(eur("5.00"), eur("-5.00")).sum())
    }
}
