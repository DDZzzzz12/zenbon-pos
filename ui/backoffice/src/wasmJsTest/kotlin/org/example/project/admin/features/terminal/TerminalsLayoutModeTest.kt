package org.example.project.admin.features.terminal

import org.example.project.admin.features.terminal.presentation.TerminalsLayoutMode
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalsLayoutModeTest {
    @Test
    fun contentUnder600IsCompact() {
        assertEquals(TerminalsLayoutMode.COMPACT, TerminalsLayoutMode.forContentWidth(358f))
        assertEquals(TerminalsLayoutMode.COMPACT, TerminalsLayoutMode.forContentWidth(599.9f))
    }

    @Test
    fun contentFrom600IsMediumUntilTheTableAndPanelFit() {
        assertEquals(TerminalsLayoutMode.MEDIUM, TerminalsLayoutMode.forContentWidth(600f))
        assertEquals(TerminalsLayoutMode.MEDIUM, TerminalsLayoutMode.forContentWidth(736f))
        assertEquals(TerminalsLayoutMode.MEDIUM, TerminalsLayoutMode.forContentWidth(1119.9f))
    }

    @Test
    fun contentFrom1120IsLarge() {
        assertEquals(TerminalsLayoutMode.LARGE, TerminalsLayoutMode.forContentWidth(1120f))
        assertEquals(TerminalsLayoutMode.LARGE, TerminalsLayoutMode.forContentWidth(1376f))
    }
}
