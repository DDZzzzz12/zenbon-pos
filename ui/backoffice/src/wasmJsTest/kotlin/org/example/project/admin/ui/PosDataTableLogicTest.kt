package org.example.project.admin.ui

import org.example.project.ui.components.normalizeCells
import kotlin.test.Test
import kotlin.test.assertEquals

class PosDataTableLogicTest {
    @Test
    fun padsShortRowToColumnCount() {
        assertEquals(listOf("a", "b", ""), normalizeCells(listOf("a", "b"), 3))
    }

    @Test
    fun truncatesLongRowToColumnCount() {
        assertEquals(listOf("a", "b"), normalizeCells(listOf("a", "b", "c"), 2))
    }

    @Test
    fun exactRowUnchanged() {
        assertEquals(listOf("a", "b"), normalizeCells(listOf("a", "b"), 2))
    }
}
