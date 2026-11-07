package org.example.project.banking.mt940

import kotlinx.datetime.LocalDate
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Mt940ValuesTest {
    @Test
    fun `parses a comma decimal`() {
        assertEquals(0, BigDecimal("1234.56").compareTo(Mt940Values.amount("1234,56")))
    }

    @Test
    fun `parses an amount above one thousand without grouping`() {
        // "1234,56" naive-parsed with the comma stripped becomes 123456 — a hundredfold error.
        assertEquals(0, BigDecimal("1234.56").compareTo(Mt940Values.amount("1234,56")))
        assertEquals(0, BigDecimal("10000.00").compareTo(Mt940Values.amount("10000,00")))
    }

    @Test
    fun `parses an amount with no decimal part`() {
        assertEquals(0, BigDecimal("500").compareTo(Mt940Values.amount("500,")))
    }

    @Test
    fun `refuses an unparseable amount rather than defaulting to zero`() {
        assertFailsWith<Mt940ParseException> { Mt940Values.amount("abc") }
    }

    @Test
    fun `parses a six-digit date into the 2000s`() {
        assertEquals(LocalDate(2026, 1, 15), Mt940Values.date("260115"))
    }

    @Test
    fun `refuses a malformed date`() {
        assertFailsWith<Mt940ParseException> { Mt940Values.date("2601") }
        assertFailsWith<Mt940ParseException> { Mt940Values.date("269915") }
    }

    @Test
    fun `booking date takes the value date's year in the ordinary case`() {
        assertEquals(LocalDate(2026, 1, 20), Mt940Values.bookingDate("0120", LocalDate(2026, 1, 21)))
    }

    @Test
    fun `a December booking against a January value date belongs to the previous year`() {
        assertEquals(LocalDate(2025, 12, 31), Mt940Values.bookingDate("1231", LocalDate(2026, 1, 3)))
    }

    @Test
    fun `a January booking against a December value date belongs to the next year`() {
        assertEquals(LocalDate(2027, 1, 2), Mt940Values.bookingDate("0102", LocalDate(2026, 12, 30)))
    }

    @Test
    fun `an absent booking date falls back to the value date`() {
        assertEquals(LocalDate(2026, 5, 4), Mt940Values.bookingDate(null, LocalDate(2026, 5, 4)))
    }
}
