package org.example.project.ui.layout

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaneBreakpointsTest {
    @Test
    fun belowPhoneMaxIsSingle() {
        assertEquals(PaneLayout.Single, PaneBreakpoints.layoutFor(390.dp))
        assertEquals(PaneLayout.Single, PaneBreakpoints.layoutFor(599.dp))
    }

    @Test
    fun betweenPhoneMaxAndTwoColumnsStacks() {
        assertEquals(PaneLayout.Stacked, PaneBreakpoints.layoutFor(600.dp))
        assertEquals(PaneLayout.Stacked, PaneBreakpoints.layoutFor(839.dp))
    }

    @Test
    fun columnsScaleWithWidthUpToMax() {
        assertEquals(PaneLayout.Columns(2), PaneBreakpoints.layoutFor(840.dp))
        assertEquals(PaneLayout.Columns(2), PaneBreakpoints.layoutFor(1259.dp))
        assertEquals(PaneLayout.Columns(3), PaneBreakpoints.layoutFor(1260.dp))
        assertEquals(PaneLayout.Columns(3), PaneBreakpoints.layoutFor(3000.dp))
    }

    @Test
    fun maxColumnsCapsTheCount() {
        assertEquals(PaneLayout.Columns(2), PaneBreakpoints.layoutFor(3000.dp, maxColumns = 2))
        assertEquals(PaneLayout.Stacked, PaneBreakpoints.layoutFor(3000.dp, maxColumns = 1))
    }

    @Test
    fun maxColumnsBelowOneIsRejected() {
        assertFailsWith<IllegalArgumentException> { PaneBreakpoints.layoutFor(800.dp, maxColumns = 0) }
        Unit
    }

    @Test
    fun drillOnPhoneShowsOnePaneAtATime() {
        assertEquals(DrillVisibility.ListOnly, PaneBreakpoints.drillVisibility(390.dp, detailOpen = false))
        assertEquals(DrillVisibility.DetailOnly, PaneBreakpoints.drillVisibility(390.dp, detailOpen = true))
    }

    @Test
    fun drillAtOrAbovePhoneMaxShowsBoth() {
        assertEquals(DrillVisibility.Both, PaneBreakpoints.drillVisibility(600.dp, detailOpen = false))
        assertEquals(DrillVisibility.Both, PaneBreakpoints.drillVisibility(1400.dp, detailOpen = true))
    }

    @Test
    fun drillIsOptIn() {
        // A caller that supplies no way back never gets the drill: it keeps the stacked/side-by-side layout.
        assertEquals(DrillVisibility.Both, PaneBreakpoints.drillVisibility(390.dp, detailOpen = true, optedIn = false))
        assertEquals(DrillVisibility.Both, PaneBreakpoints.drillVisibility(390.dp, detailOpen = false, optedIn = false))
        assertEquals(DrillVisibility.DetailOnly, PaneBreakpoints.drillVisibility(390.dp, detailOpen = true, optedIn = true))
        assertEquals(DrillVisibility.ListOnly, PaneBreakpoints.drillVisibility(390.dp, detailOpen = false, optedIn = true))
        assertEquals(DrillVisibility.Both, PaneBreakpoints.drillVisibility(600.dp, detailOpen = true, optedIn = true))
    }

    @Test
    fun phoneDetailNeedsPhoneWidthOpenDetailAndSelection() {
        assertEquals(true, PaneBreakpoints.isPhoneDetail(390.dp, detailOpen = true, hasSelection = true))
        assertEquals(false, PaneBreakpoints.isPhoneDetail(390.dp, detailOpen = false, hasSelection = true))
        assertEquals(false, PaneBreakpoints.isPhoneDetail(390.dp, detailOpen = true, hasSelection = false))
        assertEquals(false, PaneBreakpoints.isPhoneDetail(600.dp, detailOpen = true, hasSelection = true))
        assertEquals(false, PaneBreakpoints.isPhoneDetail(1400.dp, detailOpen = true, hasSelection = true))
    }

    @Test
    fun drillsAcrossTheStackedBandWhenSplitMinIsWider() {
        assertEquals(DrillVisibility.DetailOnly, PaneBreakpoints.drillVisibility(700.dp, true, splitMin = 900.dp))
        assertEquals(DrillVisibility.ListOnly, PaneBreakpoints.drillVisibility(700.dp, false, splitMin = 900.dp))
        assertEquals(DrillVisibility.Both, PaneBreakpoints.drillVisibility(900.dp, true, splitMin = 900.dp))
    }

    @Test
    fun defaultSplitMinKeepsTheOldPhoneBoundary() {
        assertEquals(DrillVisibility.Both, PaneBreakpoints.drillVisibility(600.dp, true))
        assertEquals(DrillVisibility.DetailOnly, PaneBreakpoints.drillVisibility(599.dp, true))
    }

    @Test
    fun notOptedInNeverDrills() {
        assertEquals(DrillVisibility.Both, PaneBreakpoints.drillVisibility(300.dp, true, optedIn = false, splitMin = 900.dp))
    }

    @Test
    fun phoneDetailFollowsSplitMin() {
        assertTrue(PaneBreakpoints.isPhoneDetail(700.dp, true, true, splitMin = 900.dp))
        assertFalse(PaneBreakpoints.isPhoneDetail(700.dp, true, true))
    }

    @Test
    fun gutterCountsTowardsTheScreenWidth() {
        // 596dp measured inside two 16dp gutters is a 628dp screen
        assertEquals(PaneLayout.Stacked, PaneBreakpoints.layoutFor(596.dp, gutter = 16.dp))
        assertEquals(PaneLayout.Single, PaneBreakpoints.layoutFor(596.dp))
    }

    @Test
    fun gutterDoesNotAddColumns() {
        // 820dp of content is one 420dp column wide; the gutters are not a second one
        assertEquals(PaneLayout.Stacked, PaneBreakpoints.layoutFor(820.dp, gutter = 16.dp))
        assertEquals(PaneLayout.Columns(2), PaneBreakpoints.layoutFor(840.dp, gutter = 16.dp))
    }

    @Test
    fun shortWindow() {
        assertTrue(PaneBreakpoints.isShortWindow(390.dp))
        assertFalse(PaneBreakpoints.isShortWindow(480.dp))
    }

    @Test
    fun masonryPutsEachSectionUnderTheShortestColumn() {
        assertEquals(listOf(0, 1, 0, 0), PaneBreakpoints.balancedColumns(listOf(100, 900, 300, 300), 2))
        assertEquals(listOf(0, 1, 2), PaneBreakpoints.balancedColumns(listOf(5, 5, 5), 3))
    }
}
