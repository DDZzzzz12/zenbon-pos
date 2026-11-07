package org.example.project.admin.features.terminal

import org.example.project.admin.features.terminal.presentation.CreateTerminalDraft
import org.example.project.admin.features.terminal.presentation.TerminalEditDraft
import org.example.project.admin.features.terminal.presentation.TerminalsState
import org.example.project.core.domain.enums.terminal.TerminalRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What "unsaved" means for the Terminals edit panel and the create dialog (the leave guard's input). */
class TerminalDirtyDraftTest {
    private val stored = TerminalTestFixtures.summary(id = 1, name = "Kasse 1")

    private fun data(edit: TerminalEditDraft?) = TerminalsState.Data(all = listOf(stored), selectedId = 1, edit = edit)

    @Test
    fun `the baseline is the stored terminal with blanks for missing values`() {
        val baseline = TerminalEditDraft.of(TerminalTestFixtures.summary(id = 2))
        assertEquals("", baseline.name)
        assertEquals("", baseline.location)
        assertEquals(TerminalRole.CASHIER, baseline.role)
    }

    @Test
    fun `no edit open is clean`() {
        assertFalse(data(null).isEditDirty)
    }

    @Test
    fun `an edit that was only opened is clean`() {
        assertFalse(data(TerminalEditDraft.of(stored)).isEditDirty)
    }

    @Test
    fun `changing the name makes the edit dirty and putting it back makes it clean`() {
        val opened = TerminalEditDraft.of(stored)
        assertTrue(data(opened.copy(name = "Kasse 2")).isEditDirty)
        assertFalse(data(opened.copy(name = "Kasse 2").copy(name = "Kasse 1")).isEditDirty)
    }

    @Test
    fun `location and role count and the save flags do not`() {
        val opened = TerminalEditDraft.of(stored)
        assertTrue(data(opened.copy(location = "Bar")).isEditDirty)
        assertTrue(data(opened.copy(role = TerminalRole.KITCHEN)).isEditDirty)
        assertFalse(data(opened.copy(saving = true)).isEditDirty)
    }

    @Test
    fun `an untouched create dialog is clean`() {
        assertFalse(CreateTerminalDraft().isDirty)
        assertFalse(CreateTerminalDraft(name = "   ").isDirty)
    }

    @Test
    fun `a typed name or a changed role makes the create dialog dirty`() {
        assertTrue(CreateTerminalDraft(name = "Bar").isDirty)
        assertTrue(CreateTerminalDraft(role = TerminalRole.KITCHEN).isDirty)
    }
}
