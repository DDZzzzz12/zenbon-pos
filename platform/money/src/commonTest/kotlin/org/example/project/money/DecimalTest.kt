package org.example.project.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecimalTest {
    @Test
    fun parsesAndPrintsPlainStrings() {
        assertEquals("16.90", Decimal.parse("16.90").toPlainString())
        assertEquals("16.9000", Decimal.parse("16.9000").toPlainString())
        assertEquals("16.90", Decimal.parse("16.90").toString())
    }

    @Test
    fun parseOrNullReturnsNullInsteadOfThrowing() {
        assertNull(Decimal.parseOrNull("1,5"))
        assertNull(Decimal.parseOrNull(""))
        assertEquals("1.50", Decimal.parseOrNull("1.50")?.toPlainString())
    }

    @Test
    fun constantsHaveExpectedValues() {
        assertEquals("0", Decimal.ZERO.toPlainString())
        assertEquals("1", Decimal.ONE.toPlainString())
        assertEquals("100", Decimal.HUNDRED.toPlainString())
    }

    @Test
    fun buildsFromUnscaledAndScale() {
        assertEquals("16.90", Decimal.of(1690, 2).toPlainString())
        assertEquals("-16.90", Decimal.of(-1690, 2).toPlainString())
        assertEquals("0.0019", Decimal.of(19, 4).toPlainString())
        assertEquals("1690", Decimal.of(1690, 0).toPlainString())
    }

    @Test
    fun buildsFromIntAndLong() {
        assertEquals("17", Decimal.of(17).toPlainString())
        assertEquals("17", Decimal.of(17L).toPlainString())
    }

    @Test
    fun arithmeticFollowsBigDecimalScaleRules() {
        assertEquals("2.75", (Decimal.parse("1.5") + Decimal.parse("1.25")).toPlainString())
        assertEquals("0.25", (Decimal.parse("1.5") - Decimal.parse("1.25")).toPlainString())
        assertEquals("1.875", (Decimal.parse("1.5") * Decimal.parse("1.25")).toPlainString())
        assertEquals("-1.50", (-Decimal.parse("1.50")).toPlainString())
        assertEquals("1.50", Decimal.parse("-1.50").abs().toPlainString())
    }

    @Test
    fun divideRequiresScaleAndRoundsAsAsked() {
        assertEquals("0.3333", Decimal.parse("1").divide(Decimal.parse("3"), 4).toPlainString())
        assertEquals(
            "0.333",
            Decimal
                .parse("1")
                .divide(Decimal.parse("3"), 3, MoneyRounding.HALF_UP)
                .toPlainString(),
        )
    }

    @Test
    fun divideByZeroThrows() {
        assertFailsWith<DecimalArithmeticException> {
            Decimal.parse("1").divide(Decimal.ZERO, 2)
        }
    }

    @Test
    fun timesWithScaleRoundsImmediately() {
        assertEquals(
            "1.88",
            Decimal
                .parse("1.5")
                .times(Decimal.parse("1.25"), 2, MoneyRounding.HALF_UP)
                .toPlainString(),
        )
    }

    @Test
    fun setScalePadsAndRounds() {
        assertEquals("16.9000", Decimal.parse("16.9").setScale(4).toPlainString())
        assertEquals("16.90", Decimal.parse("16.899").setScale(2).toPlainString())
    }

    @Test
    fun equalsIsValueBasedAcrossScales() {
        assertEquals(Decimal.parse("16.90"), Decimal.parse("16.9000"))
        assertEquals(Decimal.parse("16.90").hashCode(), Decimal.parse("16.9000").hashCode())
        assertEquals(Decimal.parse("0"), Decimal.parse("0.00"))
        assertEquals(Decimal.parse("0").hashCode(), Decimal.parse("0.00").hashCode())
        assertEquals(Decimal.parse("-16.90"), Decimal.parse("-16.9000"))
        assertEquals(Decimal.parse("-16.90").hashCode(), Decimal.parse("-16.9000").hashCode())
    }

    @Test
    fun equalsExactAlsoComparesScale() {
        assertTrue(Decimal.parse("16.90").equalsExact(Decimal.parse("16.90")))
        assertFalse(Decimal.parse("16.90").equalsExact(Decimal.parse("16.9000")))
    }

    @Test
    fun valueEqualDecimalsCollapseInASet() {
        val set = setOf(Decimal.parse("16.90"), Decimal.parse("16.9000"), Decimal.parse("16.9"))
        assertEquals(1, set.size)
    }

    @Test
    fun ordersByValue() {
        val sorted =
            listOf(Decimal.parse("2.00"), Decimal.parse("-1.5"), Decimal.parse("0.1"))
                .sorted()
                .map { it.toPlainString() }
        assertEquals(listOf("-1.5", "0.1", "2.00"), sorted)
    }

    @Test
    fun exposesScalePrecisionAndSign() {
        val d = Decimal.parse("-16.9000")
        assertEquals(4, d.scale)
        assertEquals(6, d.precision)
        assertEquals(-1, d.signum)
        assertTrue(d.isNegative)
        assertFalse(d.isZero)
        assertTrue(Decimal.parse("0.00").isZero)
    }

    @Test
    fun resultsLeavingTheEnvelopeThrow() {
        // scale 7 x scale 6 = scale 13, one past the ceiling
        val scaleOverflow =
            assertFailsWith<DecimalEnvelopeException> {
                Decimal.parse("0.1234567") * Decimal.parse("0.123456")
            }
        assertEquals(
            "Value outside supported decimal envelope: scale 13 exceeds maximum 12",
            scaleOverflow.message,
        )
    }

    @Test
    fun resultsExceedingSignificantDigitsThrow() {
        // MAX_SIGNIFICANT_DIGITS is unreachable through parse (18 int + 12 scale caps it at
        // exactly 30), so arithmetic results are the ONLY place this guard can fire. An
        // 18-digit square is 36 digits, and the precision guard is checked before the
        // integer-digit guard, so this pins MAX_SIGNIFICANT_DIGITS specifically.
        val big = Decimal.parse("999999999999999999") // 18 digits, accepted
        val ex = assertFailsWith<DecimalEnvelopeException> { big * big }
        assertEquals(
            "Value outside supported decimal envelope: precision 36 exceeds maximum 30",
            ex.message,
        )
    }

    @Test
    fun toDoubleLossyIsAvailableButNamedForIt() {
        assertEquals(16.9, Decimal.parse("16.90").toDoubleLossy())
    }
}
