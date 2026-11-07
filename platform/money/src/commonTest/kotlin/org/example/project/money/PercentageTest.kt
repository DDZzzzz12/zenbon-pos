package org.example.project.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PercentageTest {
    @Test
    fun storesPercentUnitsNotFractions() {
        assertEquals("19.00", Percentage.ofPercent("19.00").toPercent().toPlainString())
        assertEquals("19", Percentage.ofPercent("19").toPercent().toPlainString())
    }

    @Test
    fun convertsPercentToFractionExactly() {
        assertEquals("0.19", Percentage.ofPercent("19").toFraction().toPlainString())
        assertEquals("0.1900", Percentage.ofPercent("19.00").toFraction().toPlainString())
        assertEquals("0.107", Percentage.ofPercent("10.7").toFraction().toPlainString())
        assertEquals("0.07", Percentage.ofPercent("7").toFraction().toPlainString())
        assertEquals("0.00", Percentage.ofPercent("0").toFraction().toPlainString())
    }

    @Test
    fun buildsFromFraction() {
        assertEquals("19", Percentage.ofFraction(Decimal.parse("0.19")).toPercent().toPlainString())
        assertEquals("10.7", Percentage.ofFraction(Decimal.parse("0.107")).toPercent().toPlainString())
    }

    @Test
    fun parsesVatRateNormalizerOutput() {
        // VatRateNormalizer emits "19" and "10.7"
        assertEquals(Percentage.ofPercent("19"), Percentage.parse("19"))
        assertEquals(Percentage.ofPercent("10.7"), Percentage.parse("10.7"))
    }

    @Test
    fun equalityIsValueBased() {
        assertEquals(Percentage.parse("19"), Percentage.parse("19.00"))
    }

    @Test
    fun addsSubtractsAndComplements() {
        assertEquals(Percentage.parse("26"), Percentage.parse("19") + Percentage.parse("7"))
        assertEquals(Percentage.parse("12"), Percentage.parse("19") - Percentage.parse("7"))
        assertEquals(Percentage.parse("90"), Percentage.parse("10").complement())
    }

    @Test
    fun zeroConstantIsZeroPercent() {
        assertEquals("0", Percentage.ZERO.toPercent().toPlainString())
    }

    @Test
    fun rejectsScaleThatWouldOverflowTheFractionScale() {
        // percent scale 10 -> fraction scale 12, the ceiling
        Percentage.ofPercent("1.0000000000")
        assertFailsWith<DecimalEnvelopeException> {
            Percentage.ofPercent("1.00000000000") // scale 11 -> fraction scale 13
        }
    }

    @Test
    fun formatsWithPercentSuffix() {
        assertEquals("19 %", Percentage.parse("19").format())
        assertEquals("10.7 %", Percentage.parse("10.7").format())
        assertEquals("10,7 %", Percentage.parse("10.7").format(PercentFormat.DE))
    }
}
