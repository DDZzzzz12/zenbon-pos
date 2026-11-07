package org.example.project.ui.components.filter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PosFilterPanelStateTest {
    @Test
    fun startsClosedWithTheAppliedValueAsDraft() {
        val state = PosFilterPanelState("applied")
        assertFalse(state.isOpen)
        assertEquals("applied", state.draft.value)
    }

    @Test
    fun openReopensTheDraftFromTheCurrentAppliedValue() {
        val state = PosFilterPanelState("a")
        state.draft.value = "abandoned edit"
        state.applied = "b"
        state.open()
        assertTrue(state.isOpen)
        assertEquals("b", state.draft.value)
    }

    @Test
    fun closeDropsTheEditOnTheNextOpen() {
        val state = PosFilterPanelState("a")
        state.open()
        state.draft.value = "edit"
        state.close()
        assertFalse(state.isOpen)
        state.open()
        assertEquals("a", state.draft.value)
    }

    @Test
    fun applyHandsOverTheDraftAndCloses() {
        val state = PosFilterPanelState("a")
        state.open()
        state.draft.value = "edit"
        var applied: String? = null
        state.applyDraft { applied = it }
        assertEquals("edit", applied)
        assertFalse(state.isOpen)
    }

    @Test
    fun resetOnAWideWindowClearsTheDraftAndAppliesAtOnce() {
        val state = PosFilterPanelState("a")
        state.open()
        state.draft.value = "edit"
        var resetApplied = false
        state.reset(compact = false, clearDraft = { "" }, applyReset = { resetApplied = true })
        assertEquals("", state.draft.value)
        assertTrue(resetApplied)
        assertTrue(state.isOpen)
    }

    @Test
    fun resetOnACompactWindowOnlyClearsTheDraft() {
        val state = PosFilterPanelState("a")
        state.open()
        state.draft.value = "edit"
        var resetApplied = false
        state.reset(compact = true, clearDraft = { it.take(1) }, applyReset = { resetApplied = true })
        assertEquals("e", state.draft.value)
        assertFalse(resetApplied)
        assertTrue(state.isOpen)
    }

    @Test
    fun closeIfEmptyKeepsAPanelThatStillHasFields() {
        val state = PosFilterPanelState("a")
        state.open()
        assertFalse(state.closeIfEmpty(hasFields = true))
        assertTrue(state.isOpen)
    }

    @Test
    fun closeIfEmptyClosesAPanelWithNothingToShow() {
        val state = PosFilterPanelState("a")
        state.open()
        assertTrue(state.closeIfEmpty(hasFields = false))
        assertFalse(state.isOpen)
        // The next open starts afresh.
        state.draft.value = "stale"
        state.open()
        assertEquals("a", state.draft.value)
    }
}
