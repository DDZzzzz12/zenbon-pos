package org.example.project.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoneyExceptionTest {
    @Test
    fun allExceptionsShareTheMoneyExceptionRoot() {
        val all: List<MoneyException> =
            listOf(
                DecimalParseException("x", "bad"),
                DecimalEnvelopeException("too wide"),
                DecimalArithmeticException("divide by zero"),
                CurrencyFormatException("EU"),
                CurrencyMismatchException("EUR", "USD"),
                MoneyAllocationException("parts must be >= 1"),
                MoneyEmptySumException(),
            )
        assertEquals(7, all.size)
        assertTrue(all.all { it is RuntimeException })
    }

    @Test
    fun parseExceptionMessageNamesInputAndReason() {
        val e = DecimalParseException("1,5", "unexpected character")
        assertTrue(e.message!!.contains("1,5"))
        assertTrue(e.message!!.contains("unexpected character"))
    }

    @Test
    fun mismatchExceptionMessageNamesBothCurrencies() {
        val e = CurrencyMismatchException("EUR", "USD")
        assertTrue(e.message!!.contains("EUR"))
        assertTrue(e.message!!.contains("USD"))
    }

    @Test
    fun roundingModesCoverTheSixSupportedPolicies() {
        assertEquals(6, MoneyRounding.entries.size)
    }
}
