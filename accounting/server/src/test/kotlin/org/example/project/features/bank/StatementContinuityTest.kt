package org.example.project.features.bank

import org.example.project.features.bank.data.service.StatementBalanceLink
import org.example.project.features.bank.data.service.findContinuityBreaks
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

private fun link(
    id: Long,
    opening: String?,
    closing: String?,
) = StatementBalanceLink(id, opening?.let(::BigDecimal), closing?.let(::BigDecimal))

class StatementContinuityTest {
    @Test
    fun `a clean chain has no breaks`() {
        val breaks =
            findContinuityBreaks(
                listOf(link(1, "0.00", "100.00"), link(2, "100.00", "250.00"), link(3, "250.00", "80.00")),
            )
        assertEquals(emptyList(), breaks)
    }

    @Test
    fun `a gap is reported against the later statement`() {
        val breaks =
            findContinuityBreaks(
                listOf(link(1, "0.00", "100.00"), link(3, "180.00", "250.00")),
            )
        assertEquals(listOf(3L), breaks)
    }

    @Test
    fun `scale differences are not breaks`() {
        val breaks =
            findContinuityBreaks(
                listOf(link(1, "0.00", "100.0000"), link(2, "100.00", "250.00")),
            )
        assertEquals(emptyList(), breaks, "BigDecimal.equals is scale-sensitive; compareTo is not")
    }

    @Test
    fun `a missing balance breaks the chain rather than being assumed`() {
        val breaks =
            findContinuityBreaks(
                listOf(link(1, "0.00", null), link(2, "100.00", "250.00"), link(3, "250.00", "300.00")),
            )
        assertEquals(listOf(2L), breaks)
    }

    @Test
    fun `a single statement cannot break`() {
        assertEquals(emptyList(), findContinuityBreaks(listOf(link(1, "0.00", "100.00"))))
    }

    @Test
    fun `an empty list is not an error`() {
        assertEquals(emptyList(), findContinuityBreaks(emptyList()))
    }
}
