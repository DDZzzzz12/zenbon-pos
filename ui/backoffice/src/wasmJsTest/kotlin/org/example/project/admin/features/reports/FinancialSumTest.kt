package org.example.project.admin.features.reports

import org.example.project.admin.features.reports.presentation.components.sumMoneyOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FinancialSumTest {
    @Test
    fun sumsValidAmounts() {
        assertEquals("36423.21", sumMoneyOrNull(listOf("33703.21", "2360.00", "360.00")))
    }

    @Test
    fun emptyListSumsToZero() {
        assertEquals("0", sumMoneyOrNull(emptyList()))
    }

    @Test
    fun returnsNullOnUnparseable() {
        assertNull(sumMoneyOrNull(listOf("10.00", "not-a-number")))
    }
}
