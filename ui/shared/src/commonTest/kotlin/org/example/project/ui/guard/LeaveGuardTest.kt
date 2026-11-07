package org.example.project.ui.guard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeaveGuardTest {
    private class Probe(
        var dirty: Boolean,
    ) {
        var discards = 0
        val source = DirtySource({ dirty }, { discards++ })
    }

    @Test
    fun cleanLeaveRunsAtOnce() {
        val guard = LeaveGuard()
        guard.register(Probe(dirty = false).source)
        var left = 0
        guard.requestLeave { left++ }
        assertEquals(1, left)
        assertNull(guard.pending)
    }

    @Test
    fun dirtyLeaveAsksAndDoesNotRun() {
        val guard = LeaveGuard()
        guard.register(Probe(dirty = true).source)
        var left = 0
        guard.requestLeave { left++ }
        assertEquals(0, left)
        assertNotNull(guard.pending)
    }

    @Test
    fun keepEditingDropsThePromptAndNeverLeaves() {
        val guard = LeaveGuard()
        val probe = Probe(dirty = true)
        guard.register(probe.source)
        var left = 0
        guard.requestLeave { left++ }
        guard.keepEditing()
        assertNull(guard.pending)
        assertEquals(0, left)
        assertEquals(0, probe.discards)
    }

    @Test
    fun discardDropsOnlyDirtySourcesThenLeavesOnce() {
        val guard = LeaveGuard()
        val dirty = Probe(dirty = true)
        val clean = Probe(dirty = false)
        guard.register(dirty.source)
        guard.register(clean.source)
        var left = 0
        guard.requestLeave { left++ }
        guard.discard()
        guard.discard()
        assertEquals(1, dirty.discards)
        assertEquals(0, clean.discards)
        assertEquals(1, left)
        assertNull(guard.pending)
    }

    @Test
    fun secondRequestWhilePromptIsOpenIsIgnored() {
        val guard = LeaveGuard()
        guard.register(Probe(dirty = true).source)
        var first = 0
        var second = 0
        guard.requestLeave { first++ }
        guard.requestLeave { second++ }
        guard.discard()
        assertEquals(1, first)
        assertEquals(0, second)
    }

    @Test
    fun cleanCloseDiscardsAtOnce() {
        val guard = LeaveGuard()
        val probe = Probe(dirty = false)
        guard.register(probe.source)
        guard.requestClose(probe.source)
        assertEquals(1, probe.discards)
        assertNull(guard.pending)
    }

    @Test
    fun dirtyCloseAsksAboutThatSourceAlone() {
        val guard = LeaveGuard()
        val page = Probe(dirty = true)
        val dialog = Probe(dirty = true)
        guard.register(page.source)
        guard.register(dialog.source)
        guard.requestClose(dialog.source)
        assertEquals(listOf(dialog.source), guard.pending?.sources)
        guard.discard()
        assertEquals(1, dialog.discards)
        assertEquals(0, page.discards)
    }

    @Test
    fun unregisteredSourceIsIgnored() {
        val guard = LeaveGuard()
        val probe = Probe(dirty = true)
        guard.register(probe.source)
        guard.unregister(probe.source)
        var left = 0
        guard.requestLeave { left++ }
        assertEquals(1, left)
    }

    @Test
    fun inertGuardNeverAsks() {
        val guard = LeaveGuard.Inert
        guard.register(Probe(dirty = true).source)
        var left = 0
        guard.requestLeave { left++ }
        assertEquals(1, left)
        assertFalse(guard.isDirty)
    }

    @Test
    fun editThenUndoIsClean() {
        val guard = LeaveGuard()
        val probe = Probe(dirty = true)
        guard.register(probe.source)
        assertTrue(guard.isDirty)
        probe.dirty = false
        var left = 0
        guard.requestLeave { left++ }
        assertEquals(1, left)
        assertFalse(guard.isDirty)
    }

    @Test
    fun clearDropsAnOpenPrompt() {
        val guard = LeaveGuard()
        guard.register(Probe(dirty = true).source)
        guard.requestLeave { }
        guard.clear()
        assertNull(guard.pending)
    }
}
