package org.example.project.features.settings.presentation.components.table

import org.example.project.core.data.models.RTable
import org.example.project.core.presentation.strings.keys.CommonKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Option A for the table grid/cards: the add/save actions are never disabled for an empty number;
 * pressing them validates, records the error, bumps the attempt and creates/updates nothing.
 */
class TableValidationTest {
    private val locations = listOf(10L to "Innen", 20L to "Terrasse")

    private fun table(number: String = "5") =
        RTable(
            id = 7,
            tableNumber = number,
            seats = 4,
            locationTypeId = 10,
            locationTypeName = "Innen",
            tableStatus = "FREE",
            currentSessionId = null,
            gridX = null,
            gridY = null,
            rotation = null,
        )

    @Test
    fun blankNumber_isRequired() {
        assertEquals(mapOf(TableFormField.NUMBER to CommonKeys.fieldRequired), validateTableInput(""))
        assertEquals(mapOf(TableFormField.NUMBER to CommonKeys.fieldRequired), validateTableInput("   "))
        assertTrue(validateTableInput("12").isEmpty())
    }

    @Test
    fun newDraft_emptySubmit_recordsTheErrorBumpsTheAttemptAndCreatesNothing() {
        val draft = NewTableDraft(initialLocationTypeId = 10)
        val created = mutableListOf<Triple<String, Int?, Long>>()
        draft.submit(locations) { n, s, l -> created += Triple(n, s, l) }
        assertTrue(created.isEmpty())
        assertEquals(mapOf(TableFormField.NUMBER to CommonKeys.fieldRequired), draft.fieldErrors)
        assertEquals(1, draft.validationAttempt)
        draft.submit(locations) { n, s, l -> created += Triple(n, s, l) }
        assertEquals(2, draft.validationAttempt)
        // A failed submit is not "unsaved input": the leave guard must not start asking.
        assertEquals(false, draft.isDirty)
    }

    @Test
    fun newDraft_typingTheNumberClearsItsError_thenSubmitCreatesAndResets() {
        val draft = NewTableDraft(initialLocationTypeId = 20)
        val created = mutableListOf<Triple<String, Int?, Long>>()
        draft.submit(locations) { n, s, l -> created += Triple(n, s, l) }
        draft.numberText = "12"
        assertTrue(draft.fieldErrors.isEmpty())
        draft.seatsText = "4"
        draft.submit(locations) { n, s, l -> created += Triple(n, s, l) }
        assertEquals<List<Triple<String, Int?, Long>>>(listOf(Triple("12", 4, 20L)), created)
        assertEquals("", draft.numberText)
        assertEquals<Long?>(10L, draft.locationTypeId)
    }

    @Test
    fun rowEdit_clearedNumber_isRefusedOnSave() {
        val row = table()
        val edit = TableRowEdit(row)
        val updates = mutableListOf<String>()
        edit.numberText = ""
        edit.submit(row.id) { _, n, _, _ -> updates += n }
        assertTrue(updates.isEmpty())
        assertEquals(mapOf(TableFormField.NUMBER to CommonKeys.fieldRequired), edit.fieldErrors)
        assertEquals(1, edit.validationAttempt)
        assertTrue(edit.isDirty(row))

        edit.numberText = "6"
        assertTrue(edit.fieldErrors.isEmpty())
        edit.submit(row.id) { _, n, _, _ -> updates += n }
        assertEquals(listOf("6"), updates)
    }
}
