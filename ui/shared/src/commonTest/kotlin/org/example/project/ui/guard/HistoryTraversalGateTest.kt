package org.example.project.ui.guard

import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryTraversalGateTest {
    @Test
    fun typedHashIsNeverHeld() {
        assertEquals(HistoryTraversalGate.Decision.Pass, HistoryTraversalGate().onPopState(hasState = false, dirty = true))
    }

    @Test
    fun cleanTraversalPasses() {
        assertEquals(HistoryTraversalGate.Decision.Pass, HistoryTraversalGate().onPopState(hasState = true, dirty = false))
    }

    @Test
    fun dirtyTraversalIsHeld() {
        assertEquals(HistoryTraversalGate.Decision.Hold, HistoryTraversalGate().onPopState(hasState = true, dirty = true))
    }
}
