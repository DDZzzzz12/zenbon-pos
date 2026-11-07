package org.example.project.admin.core.presentation.components

import androidx.compose.ui.unit.dp
import org.example.project.core.utils.touchTargetFor
import kotlin.test.Test
import kotlin.test.assertEquals

class AdminTouchSizeTest {
    @Test
    fun expandedWindowKeepsTheBaseSize() {
        assertEquals(28.dp, touchTargetFor(28.dp, expandedWindow = true))
        assertEquals(40.dp, touchTargetFor(40.dp, expandedWindow = true))
    }

    @Test
    fun otherWindowsRaiseSmallTargetsTo48() {
        assertEquals(48.dp, touchTargetFor(28.dp, expandedWindow = false))
        assertEquals(48.dp, touchTargetFor(40.dp, expandedWindow = false))
    }

    @Test
    fun aLargerBaseIsNeverShrunk() {
        assertEquals(56.dp, touchTargetFor(56.dp, expandedWindow = true))
        assertEquals(56.dp, touchTargetFor(56.dp, expandedWindow = false))
    }
}
