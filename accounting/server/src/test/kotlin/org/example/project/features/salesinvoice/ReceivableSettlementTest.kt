package org.example.project.features.salesinvoice

import org.example.project.features.salesinvoice.domain.service.ReceivableSettlement
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * The spec's worked mixed tender (§5.2a) and the invariant it exists to protect:
 * `prepaid + due == total`, whatever the tender looks like.
 */
class ReceivableSettlementTest {
    private fun eur(v: String) = BigDecimal(v)

    @Test
    fun `the worked mixed tender splits 80 into 30 paid and 50 owed`() {
        val split = ReceivableSettlement.split(total = eur("80.00"), receivableTender = eur("50.00"))

        assertEquals(0, eur("30.00").compareTo(split.prepaid), "the cash share is prepaid")
        assertEquals(0, eur("50.00").compareTo(split.due), "the receivable share is owed")
        assertEquals(0, eur("80.00").compareTo(split.prepaid + split.due), "prepaid + due == total")
    }

    @Test
    fun `a fully on-account bill owes everything and prepays nothing`() {
        val split = ReceivableSettlement.split(total = eur("80.00"), receivableTender = eur("80.00"))

        assertEquals(0, BigDecimal.ZERO.compareTo(split.prepaid))
        assertEquals(0, eur("80.00").compareTo(split.due))
    }

    @Test
    fun `a receivable share larger than the bill cannot make prepaid negative`() {
        // A sum over payment rows that exceeds the bill is a bug elsewhere; it must not reach a
        // §14 document as a negative prepaid.
        val split = ReceivableSettlement.split(total = eur("80.00"), receivableTender = eur("120.00"))

        assertEquals(0, BigDecimal.ZERO.compareTo(split.prepaid))
        assertEquals(0, eur("80.00").compareTo(split.due))
        assertEquals(0, eur("80.00").compareTo(split.prepaid + split.due))
    }

    @Test
    fun `no receivable tender leaves the whole total prepaid`() {
        val split = ReceivableSettlement.split(total = eur("80.00"), receivableTender = BigDecimal.ZERO)

        assertEquals(0, eur("80.00").compareTo(split.prepaid))
        assertEquals(0, BigDecimal.ZERO.compareTo(split.due))
    }
}
