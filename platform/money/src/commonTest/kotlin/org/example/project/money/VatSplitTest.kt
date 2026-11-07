package org.example.project.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VatSplitTest {
    private fun eur(text: String) = Money(Decimal.parse(text), Currency.EUR)

    private val vat19 = Percentage.parse("19")
    private val vat7 = Percentage.parse("7")

    @Test
    fun percentageOfComputesShare() {
        assertEquals(eur("19.00"), eur("100.00").percentageOf(vat19))
        assertEquals(eur("0.00"), eur("100.00").percentageOf(Percentage.ZERO))
    }

    @Test
    fun plusPercentageIsMarkup() {
        assertEquals(eur("119.00"), eur("100.00").plusPercentage(vat19))
    }

    @Test
    fun minusPercentageIsDiscount() {
        assertEquals(eur("90.00"), eur("100.00").minusPercentage(Percentage.parse("10")))
    }

    @Test
    fun ratioToGivesShareOfTotal() {
        assertEquals(Percentage.parse("12.50"), eur("25.00").ratioTo(eur("200.00")))
    }

    @Test
    fun ratioToZeroThrows() {
        assertFailsWith<DecimalArithmeticException> { eur("25.00").ratioTo(eur("0.00")) }
    }

    @Test
    fun grossSplitMatchesTheServerFormula() {
        // net = gross / (1 + fraction) at scale 2 HALF_UP; vat = gross - net
        val split = eur("119.00").vatSplitFromGross(vat19)
        assertEquals(eur("119.00"), split.gross)
        assertEquals(eur("100.00"), split.net)
        assertEquals(eur("19.00"), split.vat)
    }

    @Test
    fun grossSplitOnAnAwkwardValue() {
        val split = eur("100.00").vatSplitFromGross(vat19)
        assertEquals(eur("84.03"), split.net)
        assertEquals(eur("15.97"), split.vat)
        assertEquals(split.gross, split.net + split.vat)
    }

    @Test
    fun invariantHoldsForSubCentGross() {
        val a = eur("0.01").vatSplitFromGross(vat19)
        assertEquals(eur("0.01"), a.net)
        assertEquals(eur("0.00"), a.vat)
        assertEquals(a.gross, a.net + a.vat)

        val b = eur("0.05").vatSplitFromGross(vat19)
        assertEquals(eur("0.04"), b.net)
        assertEquals(eur("0.01"), b.vat)
        assertEquals(b.gross, b.net + b.vat)
    }

    @Test
    fun zeroRateLeavesNetEqualToGross() {
        val split = eur("119.00").vatSplitFromGross(Percentage.ZERO)
        assertEquals(eur("119.00"), split.net)
        assertEquals(eur("0.00"), split.vat)
    }

    @Test
    fun negativeGrossForStornoKeepsTheInvariant() {
        val split = eur("-119.00").vatSplitFromGross(vat19)
        assertEquals(eur("-100.00"), split.net)
        assertEquals(eur("-19.00"), split.vat)
        assertEquals(split.gross, split.net + split.vat)
    }

    @Test
    fun reducedGermanRateSplits() {
        val split = eur("107.00").vatSplitFromGross(vat7)
        assertEquals(eur("100.00"), split.net)
        assertEquals(eur("7.00"), split.vat)
    }

    @Test
    fun netSplitIsTheInverseAndKeepsTheInvariant() {
        val split = eur("100.00").vatSplitFromNet(vat19)
        assertEquals(eur("119.00"), split.gross)
        assertEquals(eur("100.00"), split.net)
        assertEquals(eur("19.00"), split.vat)
        assertEquals(split.gross, split.net + split.vat)
    }

    @Test
    fun constructingAnInconsistentSplitThrows() {
        assertFailsWith<DecimalArithmeticException> {
            VatSplit(eur("119.00"), eur("100.00"), eur("18.00"), vat19)
        }
    }
}
