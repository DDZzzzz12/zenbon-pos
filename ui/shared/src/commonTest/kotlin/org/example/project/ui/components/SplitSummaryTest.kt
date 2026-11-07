package org.example.project.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class SplitSummaryTest {
    @Test
    fun saldoRowHasOneValueAtItsColumn() {
        val summary = splitSummary(listOf("Saldo", "", "", "", "+375,45 €"))
        assertEquals("Saldo", summary.label)
        assertEquals(listOf(IndexedValue(4, "+375,45 €")), summary.values)
    }

    @Test
    fun totalsRowKeepsEveryValueWithItsColumnIndex() {
        val summary = splitSummary(listOf("Summe", "1.357,43 €", "220,87 €", "1.578,30 €", "35"))
        assertEquals("Summe", summary.label)
        assertEquals(
            listOf(
                IndexedValue(1, "1.357,43 €"),
                IndexedValue(2, "220,87 €"),
                IndexedValue(3, "1.578,30 €"),
                IndexedValue(4, "35"),
            ),
            summary.values,
        )
    }

    @Test
    fun singleCellHasNoValues() {
        val summary = splitSummary(listOf("", "Summe", ""))
        assertEquals("Summe", summary.label)
        assertEquals(emptyList(), summary.values)
    }

    @Test
    fun allBlankIsEmpty() {
        val summary = splitSummary(listOf("", " ", ""))
        assertEquals("", summary.label)
        assertEquals(emptyList(), summary.values)
    }
}
