package org.example.project.utils

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * THE per-VAT-key promotion split `BillServiceImpl` signs its rows with and the Ausgangsrechnung
 * rebuilds its allowances from. Pinned here so the two can never drift apart.
 */
class PromotionGroupApportionmentTest {
    private val rates = mapOf("V19" to BigDecimal("0.19"), "V7" to BigDecimal("0.07"), "V19b" to BigDecimal("0.19"))

    private fun split(
        charged: Map<String, String>,
        billWide: String,
        items: Map<String, String> = emptyMap(),
    ) = PromotionGroupApportionment.split(
        PromotionGroupBasis(
            chargedGross = charged.mapValues { BigDecimal(it.value) },
            itemDeductions = items.mapValues { BigDecimal(it.value) },
            billWideDeduction = BigDecimal(billWide),
            rateOf = { rates.getValue(it) },
        ),
        tieBreaker = Comparator.naturalOrder(),
    )

    private fun assertMoney(
        expected: String,
        actual: BigDecimal?,
    ) = assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected, got $actual")

    @Test
    fun `a bill-wide deduction over two rates is the largest-remainder split`() {
        val result = split(mapOf("V19" to "10.00", "V7" to "5.00"), billWide = "1.00")
        assertMoney("0.67", result.billWideShares["V19"])
        assertMoney("0.33", result.billWideShares["V7"])
        assertMoney("9.33", result.postPromotionGross["V19"])
        assertMoney("4.67", result.postPromotionGross["V7"])
    }

    @Test
    fun `item deductions stay on their key and weight the bill-wide split after them`() {
        // 10 : 10 charged, 5.00 off the 7 % item: the bill-wide 1.50 splits 10 : 5.
        val result = split(mapOf("V19" to "10.00", "V7" to "10.00"), billWide = "1.50", items = mapOf("V7" to "5.00"))
        assertMoney("1.00", result.billWideShares["V19"])
        assertMoney("0.50", result.billWideShares["V7"])
        assertMoney("5.00", result.itemDeductions["V7"])
        assertMoney("0", result.itemDeductions["V19"])
        assertMoney("4.50", result.postPromotionGross["V7"])
    }

    @Test
    fun `two keys at one rate share that rate's portion in a second pass`() {
        val result = split(mapOf("V19" to "10.00", "V19b" to "10.00", "V7" to "10.00"), billWide = "0.03")
        val at19 = result.billWideShares.getValue("V19") + result.billWideShares.getValue("V19b")
        assertMoney("0.02", at19)
        assertMoney("0.01", result.billWideShares["V7"])
        assertMoney("0.03", result.billWideShares.values.fold(BigDecimal.ZERO) { acc, v -> acc + v })
    }

    @Test
    fun `nothing is split without a positive deduction or weight`() {
        assertMoney("0", split(mapOf("V19" to "10.00"), billWide = "0").billWideShares["V19"])
        assertMoney("0", split(mapOf("V19" to "0.00"), billWide = "1.00").billWideShares["V19"])
        assertEquals(listOf("V19", "V7"), split(mapOf("V19" to "1", "V7" to "1"), "0").postPromotionGross.keys.toList(), "basis order kept")
    }
}
