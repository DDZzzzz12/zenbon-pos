package org.example.project.admin.features.allocations

import org.example.project.admin.features.allocations.presentation.AllocationsState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `editorDirty` is the part of what `clearedEditor()` resets that the operator typed or chose. The
 * loaded candidate lists, the suggestions and the target-kind tab are not input and must not prompt.
 */
class AllocationsEditorDirtyTest {
    private val clean = AllocationsState(expandedTransactionId = 7)

    @Test
    fun `an opened editor with nothing entered is clean`() {
        assertFalse(clean.editorDirty)
    }

    @Test
    fun `a typed amount makes it dirty`() {
        assertTrue(clean.copy(amountDrafts = mapOf("INVOICE:3" to "10,00")).editorDirty)
    }

    @Test
    fun `an emptied amount draft is clean again`() {
        assertFalse(clean.copy(amountDrafts = mapOf("INVOICE:3" to "")).editorDirty)
    }

    @Test
    fun `a booking text makes it dirty`() {
        assertTrue(clean.copy(bookingTextDraft = "Porto").editorDirty)
    }

    @Test
    fun `an account number makes it dirty`() {
        assertTrue(clean.copy(accountNoDraft = "4930").editorDirty)
    }

    @Test
    fun `a picked counter leg makes it dirty`() {
        assertTrue(clean.copy(selectedCounterId = 5).editorDirty)
    }

    @Test
    fun `only switching the target kind tab is not unsaved input`() {
        assertFalse(clean.copy(targetKind = "SALES_INVOICE").editorDirty)
    }
}
