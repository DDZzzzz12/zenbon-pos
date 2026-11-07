package org.example.project.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoneyAllocationTest {
    private fun eur(text: String) = Money(Decimal.parse(text), Currency.EUR)

    private fun plain(parts: List<Money>) = parts.map { it.toPlainString() }

    @Test
    fun equalSplitPutsRemainderOnTheFirstParts() {
        assertEquals(listOf("3.34", "3.33", "3.33"), plain(eur("10.00").allocate(3)))
    }

    @Test
    fun equalSplitAlwaysSumsToTheOriginal() {
        for (cents in 1..200) {
            val total = Money(Decimal.of(cents.toLong(), 2), Currency.EUR)
            for (parts in 1..7) {
                val allocated = total.allocate(parts)
                assertEquals(parts, allocated.size)
                assertEquals(
                    total,
                    allocated.reduce { a, b -> a + b },
                    "allocate($parts) of ${total.toPlainString()} did not sum back",
                )
            }
        }
    }

    @Test
    fun singlePartReturnsTheWholeAmount() {
        assertEquals(listOf("10.00"), plain(eur("10.00").allocate(1)))
    }

    @Test
    fun morePartsThanUnitsGivesZeros() {
        assertEquals(listOf("0.01", "0.01", "0.00", "0.00", "0.00"), plain(eur("0.02").allocate(5)))
    }

    @Test
    fun partsDifferByAtMostOneMinorUnit() {
        val parts = eur("10.00").allocate(3).map { it.amount }
        val max = parts.max()
        val min = parts.min()
        assertTrue((max - min) <= Decimal.of(1, 2), "spread was ${(max - min).toPlainString()}")
    }

    @Test
    fun allocationIsSignSymmetric() {
        val positive = eur("10.00").allocate(3)
        val negative = eur("-10.00").allocate(3)
        assertEquals(positive.map { -it }, negative)
        assertEquals(eur("-10.00"), negative.reduce { a, b -> a + b })
    }

    @Test
    fun rejectsNonPositivePartCount() {
        assertFailsWith<MoneyAllocationException> { eur("10.00").allocate(0) }
        assertFailsWith<MoneyAllocationException> { eur("10.00").allocate(-1) }
    }

    @Test
    fun refusesToRoundSilently() {
        // amount scale 4 is finer than the allocation scale 2
        assertFailsWith<MoneyAllocationException> { eur("10.0001").allocate(3, scale = 2) }
    }

    @Test
    fun weightedSplitIsProportional() {
        val weights = listOf(Decimal.parse("1"), Decimal.parse("1"), Decimal.parse("2"))
        assertEquals(listOf("25.00", "25.00", "50.00"), plain(eur("100.00").allocateByWeights(weights)))
    }

    @Test
    fun weightedSplitDistributesLeftoverByLargestRemainder() {
        val weights = listOf(Decimal.parse("1"), Decimal.parse("1"), Decimal.parse("1"))
        val parts = eur("10.00").allocateByWeights(weights)
        assertEquals(eur("10.00"), parts.reduce { a, b -> a + b })
        assertEquals(3, parts.size)
    }

    @Test
    fun weightedSplitAlwaysSumsToTheOriginal() {
        val weightSets =
            listOf(
                listOf("1", "1", "1"),
                listOf("1", "2", "3"),
                listOf("7", "11", "13", "17"),
                listOf("1", "0", "1"),
            ).map { set -> set.map { Decimal.parse(it) } }

        for (cents in 1..120) {
            val total = Money(Decimal.of(cents.toLong(), 2), Currency.EUR)
            for (weights in weightSets) {
                val parts = total.allocateByWeights(weights)
                assertEquals(weights.size, parts.size)
                assertEquals(
                    total,
                    parts.reduce { a, b -> a + b },
                    "weights $weights of ${total.toPlainString()} did not sum back",
                )
            }
        }
    }

    @Test
    fun zeroWeightReceivesNothing() {
        val weights = listOf(Decimal.parse("1"), Decimal.ZERO, Decimal.parse("1"))
        assertEquals(listOf("5.00", "0.00", "5.00"), plain(eur("10.00").allocateByWeights(weights)))
    }

    @Test
    fun singleWeightReceivesEverything() {
        assertEquals(listOf("10.00"), plain(eur("10.00").allocateByWeights(listOf(Decimal.parse("5")))))
    }

    @Test
    fun rejectsInvalidWeights() {
        assertFailsWith<MoneyAllocationException> { eur("10.00").allocateByWeights(emptyList()) }
        assertFailsWith<MoneyAllocationException> {
            eur("10.00").allocateByWeights(listOf(Decimal.ZERO, Decimal.ZERO))
        }
        assertFailsWith<MoneyAllocationException> {
            eur("10.00").allocateByWeights(listOf(Decimal.parse("-1"), Decimal.parse("2")))
        }
    }
}
