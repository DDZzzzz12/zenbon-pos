package org.example.project.features.allocation

import org.example.project.features.allocation.data.service.residualOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResidualCalculatorTest {
    @Test
    fun `no allocations leaves the whole amount open`() {
        assertEquals(BigDecimal("100.0000"), residualOf(BigDecimal("100.00"), emptyList()))
    }

    @Test
    fun `a full allocation settles it`() {
        assertEquals(BigDecimal("0.0000"), residualOf(BigDecimal("100.00"), listOf(BigDecimal("100.00"))))
    }

    @Test
    fun `several allocations sum`() {
        assertEquals(
            BigDecimal("8.0000"),
            residualOf(BigDecimal("100.00"), listOf(BigDecimal("40.00"), BigDecimal("52.00"))),
        )
    }

    @Test
    fun `scale is normalised so equal values compare equal`() {
        val residual = residualOf(BigDecimal("100.0000"), listOf(BigDecimal("100.00")))
        assertEquals(0, residual!!.compareTo(BigDecimal.ZERO))
        assertEquals(BigDecimal("0.0000"), residual, "scale must be pinned, not inherited from the input")
    }

    @Test
    fun `over-allocation goes negative rather than clamping to zero`() {
        assertEquals(
            BigDecimal("-20.0000"),
            residualOf(BigDecimal("100.00"), listOf(BigDecimal("120.00"))),
        )
    }

    @Test
    fun `a null total has no residual`() {
        assertNull(residualOf(null, listOf(BigDecimal("10.00"))))
    }
}
