package org.example.project.ui.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PosSideSheetPolicyTest {
    @Test
    fun overlaysBelow1100() {
        assertTrue(posSideSheetOverlays(700.dp))
        assertTrue(posSideSheetOverlays(1024.dp))
    }

    @Test
    fun pushesFrom1100() {
        assertFalse(posSideSheetOverlays(1100.dp))
        assertFalse(posSideSheetOverlays(1400.dp))
    }
}
