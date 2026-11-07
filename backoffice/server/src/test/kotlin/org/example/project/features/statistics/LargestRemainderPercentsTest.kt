package org.example.project.features.statistics

import org.example.project.features.statistics.data.repository.largestRemainderPercents
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The payment-mix percentages must sum to EXACTLY 100.00 — independent 2-dp rounding drifts to
 * 99.99 / 100.01, which is what showed up in the reports "Anteil" total.
 */
class LargestRemainderPercentsTest {
    private fun sum(list: List<BigDecimal>) = list.fold(BigDecimal.ZERO) { a, b -> a + b }

    @Test
    fun `three equal shares sum to exactly 100 with the remainder given to one row`() {
        val result = largestRemainderPercents(listOf(BigDecimal("10.00"), BigDecimal("10.00"), BigDecimal("10.00")))
        assertEquals(listOf("33.34", "33.33", "33.33"), result.map { it.toPlainString() })
        assertEquals(0, BigDecimal("100.00").compareTo(sum(result)))
    }

    @Test
    fun `a drifting mix is corrected to 100`() {
        // 20267.40 / 27682.00 / 291.30 (from the reports screenshot): naive rounding gave 99.99.
        val result =
            largestRemainderPercents(
                listOf(BigDecimal("20267.40"), BigDecimal("27682.00"), BigDecimal("291.30")),
            )
        assertEquals(0, BigDecimal("100.00").compareTo(sum(result)), "mix must total 100.00, got ${sum(result)}")
    }

    @Test
    fun `single method is 100`() {
        assertEquals(listOf("100.00"), largestRemainderPercents(listOf(BigDecimal("42.00"))).map { it.toPlainString() })
    }

    @Test
    fun `empty input yields empty`() {
        assertEquals(emptyList(), largestRemainderPercents(emptyList()))
    }

    @Test
    fun `zero total yields all zero`() {
        val result = largestRemainderPercents(listOf(BigDecimal.ZERO, BigDecimal.ZERO))
        assertEquals(listOf("0.00", "0.00"), result.map { it.toPlainString() })
    }
}
