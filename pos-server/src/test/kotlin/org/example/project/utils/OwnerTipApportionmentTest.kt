package org.example.project.utils

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [OwnerTipApportionment] is the ONE owner-tip split the bill signs, DSFinV-K exports and the
 * Ausgangsrechnung takes back out. Keyed per rate it must equal the single-pass apportionment the
 * export used before; keyed per entity it must split a rate's share across same-rate keys.
 */
class OwnerTipApportionmentTest {
    private fun d(v: String) = BigDecimal(v)

    private fun <K> basis(
        gross: Map<K, BigDecimal>,
        rateOf: (K) -> BigDecimal,
    ) = OwnerTipBasis(postPromotionGross = gross, chargedGross = gross, itemCount = gross.mapValues { BigDecimal.ONE }, rateOf = rateOf)

    @Test
    fun `keyed per rate it equals the single-pass apportionment`() {
        val gross = mapOf(d("0.19") to d("10.00"), d("0.07") to d("5.00"))

        val shares = OwnerTipApportionment.split(d("2.00"), basis(gross) { it }, Comparator.naturalOrder())!!

        assertEquals(PromotionApportionment.apportion(d("2.00"), gross), shares)
        assertEquals(0, d("1.33").compareTo(shares.getValue(d("0.19"))))
        assertEquals(0, d("0.67").compareTo(shares.getValue(d("0.07"))))
        Unit
    }

    @Test
    fun `two keys sharing a rate split that rate's share and the total is exact`() {
        val rates = mapOf("A" to d("0.19"), "C" to d("0.19"), "B" to d("0.07"))
        val gross = mapOf("A" to d("5.00"), "C" to d("5.00"), "B" to d("5.00"))

        val shares = OwnerTipApportionment.split(d("1.00"), basis(gross) { rates.getValue(it) }, Comparator.naturalOrder())!!

        assertEquals(0, d("1.00").compareTo(shares.values.fold(BigDecimal.ZERO) { a, v -> a + v }), "sums to the tip exactly")
        assertEquals(0, d("0.67").compareTo(shares.getValue("A") + shares.getValue("C")), "19 % holds its rate share")
        assertEquals(0, d("0.33").compareTo(shares.getValue("B")))
        Unit
    }

    @Test
    fun `no items at all gives null - the caller books a zero-rate row`() {
        assertNull(OwnerTipApportionment.split(d("2.00"), basis(emptyMap<String, BigDecimal>()) { d("0") }, Comparator.naturalOrder()))
        Unit
    }
}
