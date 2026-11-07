package org.example.project.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/** A Saldo row's label covers the empty cells after it; every value keeps its own column. */
class EmphasizedSpansTest {
    @Test
    fun aLabelSpansTheBlanksBeforeTheValue() {
        assertEquals(listOf(3, 0, 0, 1), emphasizedSpans(listOf("Kassenbestand: Ladenkasse", "", "", "2.246,94 €")))
    }

    @Test
    fun severalValuesKeepTheirColumns() {
        assertEquals(listOf(1, 1, 1, 1), emphasizedSpans(listOf("Summe", "10,00 €", "", "1,90 €")))
    }

    @Test
    fun leadingBlanksStayInPlace() {
        assertEquals(listOf(1, 1, 1), emphasizedSpans(listOf("", "Summe", "5,00 €")))
        assertEquals(listOf(1, 2, 0), emphasizedSpans(listOf("", "Summe", "")))
    }
}
