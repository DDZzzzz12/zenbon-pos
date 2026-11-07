package org.example.project.ui.components.form

import org.example.project.core.presentation.strings.StringKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dirty tracking, error bookkeeping and the save/discard transitions behind the SaveBar. */
class PosFormStateTest {
    private data class Product(
        val name: String,
        val price: String,
    )

    private val saved = Product(name = "Schnitzel", price = "14.50")
    private val required = StringKey("test.required")
    private val taken = StringKey("test.taken")

    @Test
    fun aFreshFormIsNotDirty() {
        assertFalse(PosFormState(saved).isDirty)
    }

    @Test
    fun editingMakesItDirtyAndEditingBackMakesItCleanAgain() {
        val edited = PosFormState(saved).edit { it.copy(price = "15.90") }
        assertTrue(edited.isDirty)
        assertFalse(edited.edit { it.copy(price = "14.50") }.isDirty)
    }

    @Test
    fun editingAFieldClearsOnlyThatFieldsError() {
        val form =
            PosFormState(saved)
                .withErrors(mapOf("name" to required, "price" to taken))
                .edit("name") { it.copy(name = "Wiener Schnitzel") }
        assertNull(form.errorFor("name"))
        assertEquals(taken, form.errorFor("price"))
        assertEquals(1, form.errorCount)
    }

    @Test
    fun savingMakesTheDraftTheNewBaseline() {
        val form = PosFormState(saved).edit { it.copy(price = "15.90") }.withErrors(mapOf("x" to required)).saved()
        assertFalse(form.isDirty)
        assertEquals("15.90", form.original.price)
        assertFalse(form.hasErrors)
    }

    @Test
    fun aServerVersionReturnedBySaveWins() {
        val fromServer = Product(name = "Wiener Schnitzel", price = "15.90")
        val form = PosFormState(saved).edit { it.copy(price = "15.9") }.saved(fromServer)
        assertEquals(fromServer, form.original)
        assertEquals(fromServer, form.draft)
    }

    @Test
    fun discardingRestoresTheSavedValues() {
        val form = PosFormState(saved).edit { it.copy(name = "x") }.withErrors(mapOf("name" to required)).discarded()
        assertEquals(saved, form.draft)
        assertFalse(form.isDirty)
        assertFalse(form.hasErrors)
    }
}
