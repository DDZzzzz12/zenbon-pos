package org.example.project.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** Window width picks the layout step at Material's 600 / 840 / 1200dp breakpoints. */
class PosLayoutTest {
    @Test
    fun aPhoneGetsTheCompactStep() {
        assertEquals(PosLayout.Compact, PosLayout.forWidth(0))
        assertEquals(PosLayout.Compact, PosLayout.forWidth(599))
    }

    @Test
    fun eachBreakpointStartsTheNextStep() {
        assertEquals(PosLayout.Medium, PosLayout.forWidth(600))
        assertEquals(PosLayout.Medium, PosLayout.forWidth(839))
        assertEquals(PosLayout.Expanded, PosLayout.forWidth(840))
        assertEquals(PosLayout.Expanded, PosLayout.forWidth(1199))
        assertEquals(PosLayout.Large, PosLayout.forWidth(1200))
        assertEquals(PosLayout.Large, PosLayout.forWidth(3840))
    }

    @Test
    fun listGapIsTheSameOnEveryWidth() {
        val steps = listOf(PosLayout.Compact, PosLayout.Medium, PosLayout.Expanded, PosLayout.Large)
        assertEquals(setOf(8.dp), steps.map { it.listGap }.toSet())
    }

    @Test
    fun onlyWideWindowsCapContentWidth() {
        assertEquals(Dp.Unspecified, PosLayout.Compact.maxContentWidth)
        assertEquals(Dp.Unspecified, PosLayout.Medium.maxContentWidth)
        assertEquals(960.dp, PosLayout.Expanded.maxContentWidth)
        assertEquals(1280.dp, PosLayout.Large.maxContentWidth)
    }
}
