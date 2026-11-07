package org.example.project.ui.components.filter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PosFilterPanelStoreTest {
    @Test
    fun sameKeyReturnsTheSameState() {
        val store = PosFilterPanelStore()
        val first = store.getOrCreate("ProductsRoute", "a")
        val again = store.getOrCreate("ProductsRoute", "b")
        assertSame(first, again)
        assertEquals("a", again.draft.value, "an existing state is not recreated from the later applied value")
    }

    @Test
    fun differentKeysGiveDifferentStates() {
        val store = PosFilterPanelStore()
        assertNotSame(store.getOrCreate("A", "x"), store.getOrCreate("B", "x"))
        assertEquals(2, store.size)
    }

    @Test
    fun anOpenPanelAndItsDraftSurviveALookupFromARebuiltScreen() {
        val store = PosFilterPanelStore()
        store.getOrCreate("VouchersRoute", "a").apply {
            open()
            draft.value = "typed"
        }
        val rebuilt = store.getOrCreate("VouchersRoute", "a")
        assertTrue(rebuilt.isOpen)
        assertEquals("typed", rebuilt.draft.value)
    }

    @Test
    fun clearRemovesEverything() {
        val store = PosFilterPanelStore()
        store.getOrCreate("A", 1)
        store.getOrCreate("B", 2)
        store.clear()
        assertEquals(0, store.size)
    }

    @Test
    fun clearByKeyRemovesOnlyThatKey() {
        val store = PosFilterPanelStore()
        store.getOrCreate("A", 1)
        store.getOrCreate("B", 2)
        store.clear("A")
        assertFalse(store.contains("A"))
        assertTrue(store.contains("B"))
    }

    @Test
    fun clearExceptKeepsTheNamedKey() {
        val store = PosFilterPanelStore()
        store.getOrCreate("A", 1)
        val b = store.getOrCreate("B", 2)
        store.getOrCreate("C", 3)
        store.clearExcept("B")
        assertEquals(1, store.size)
        assertSame(b, store.getOrCreate("B", 2))
    }

    @Test
    fun clearExceptNullDropsEverything() {
        val store = PosFilterPanelStore()
        store.getOrCreate("A", 1)
        store.clearExcept(null)
        assertEquals(0, store.size)
    }

    @Test
    fun returningToAClearedScreenStartsClosed() {
        val store = PosFilterPanelStore()
        store.getOrCreate("A", "x").open()
        store.clearExcept("B")
        assertFalse(store.getOrCreate("A", "x").isOpen)
    }
}
