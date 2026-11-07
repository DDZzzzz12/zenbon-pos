package org.example.project.admin.features.salesinvoices

import org.example.project.admin.features.salesinvoices.presentation.EditorLine
import org.example.project.admin.features.salesinvoices.presentation.SalesEditorTotals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SalesEditorTotalsTest {
    private fun line(
        quantity: String,
        price: String,
        rate: String = "19.00",
    ) = EditorLine(itemName = "x", quantity = quantity, grossUnitPrice = price, vatRate = rate)

    @Test
    fun theDesignExampleSplitsIntoNetVatAndGross() {
        val totals = SalesEditorTotals.of(listOf(line("1", "1785,00"), line("2", "261,80")))
        assertEquals("2308.60", totals.gross.toPlainString())
        assertEquals("1940.00", totals.net.toPlainString())
        assertEquals("19.00", totals.vatRows.single().rate)
        assertEquals(
            "368.60",
            totals.vatRows
                .single()
                .vat
                .toPlainString(),
        )
    }

    @Test
    fun vatIsTakenBySubtractionSoNetPlusVatIsGross() {
        val totals = SalesEditorTotals.of(listOf(line("1", "10,00"), line("1", "5,35", "7.00")))
        val vat = totals.vatRows.fold(totals.net) { sum, row -> sum + row.vat }
        assertEquals(totals.gross.toPlainString(), vat.toPlainString())
    }

    @Test
    fun eachRateGetsItsOwnVatRowInTheOrderItFirstAppears() {
        val totals = SalesEditorTotals.of(listOf(line("1", "11,90"), line("1", "10,70", "7.00"), line("1", "11,90")))
        assertEquals(listOf("19.00", "7.00"), totals.vatRows.map { it.rate })
    }

    @Test
    fun linesThatDoNotParseAreSkippedAndAnEmptyFormIsZero() {
        assertTrue(SalesEditorTotals.of(listOf(EditorLine())).vatRows.isEmpty())
        assertTrue(SalesEditorTotals.of(listOf(line("x", "5,00"))).vatRows.isEmpty())
    }
}
