package org.example.project.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyFactoryTest {
    private val factory = MoneyFactory(Currency.EUR)

    @Test
    fun buildsMoneyFromText() {
        assertEquals(Money(Decimal.parse("16.90"), Currency.EUR), factory.of("16.90"))
    }

    @Test
    fun buildsMoneyFromDecimal() {
        assertEquals(Money(Decimal.parse("16.90"), Currency.EUR), factory.of(Decimal.parse("16.90")))
    }

    @Test
    fun buildsZero() {
        assertEquals(Money(Decimal.ZERO, Currency.EUR), factory.zero())
    }

    @Test
    fun propagatesParseFailures() {
        assertFailsWith<DecimalParseException> { factory.of("1,5") }
    }

    @Test
    fun exposesItsCurrency() {
        assertEquals(Currency.EUR, factory.currency)
    }
}
