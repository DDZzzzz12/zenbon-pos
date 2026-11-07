package org.example.project.ui.components.filter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PosFilterDraftTest {
    @Test
    fun reopenResetsValueToApplied() {
        val draft = PosFilterDraft("a")
        draft.value = "x"
        draft.reopen("applied")
        assertEquals("applied", draft.value)
    }

    @Test
    fun isChangedFalseAfterReopenTrueAfterEdit() {
        val draft = PosFilterDraft("a")
        draft.reopen("applied")
        assertFalse(draft.isChanged("applied"))
        draft.value = "edited"
        assertTrue(draft.isChanged("applied"))
    }
}
