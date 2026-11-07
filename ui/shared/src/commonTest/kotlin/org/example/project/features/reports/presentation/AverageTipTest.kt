package org.example.project.features.reports.presentation

import org.example.project.features.reports.presentation.viewmodel.averageTipPerBill
import kotlin.test.Test
import kotlin.test.assertEquals

class AverageTipTest {
    @Test
    fun dividesTotalByBillCount() {
        assertEquals("0,36 €", averageTipPerBill("2.50", 7, "EUR"))
    }

    @Test
    fun zeroBillsReadsZero() {
        assertEquals("0,00 €", averageTipPerBill("2.50", 0, "EUR"))
    }

    @Test
    fun unparseableTotalReadsZero() {
        assertEquals("0,00 €", averageTipPerBill("n/a", 3, "EUR"))
    }

    @Test
    fun roundsHalfUp() {
        // 0.05 / 2 = 0.025 -> 0.03 (half up), not 0.02 (half even)
        assertEquals("0,03 €", averageTipPerBill("0.05", 2, "EUR"))
    }
}
