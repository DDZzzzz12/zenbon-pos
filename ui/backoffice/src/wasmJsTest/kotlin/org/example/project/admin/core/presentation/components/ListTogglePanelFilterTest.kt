package org.example.project.admin.core.presentation.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListTogglePanelFilterTest {
    private val off = ListTogglePanelFilter(on = false)
    private val on = ListTogglePanelFilter(on = true)

    @Test
    fun onlyTheNonDefaultToggleIsCounted() {
        assertEquals(0, off.activeCount())
        assertEquals(1, on.activeCount())
    }

    @Test
    fun theToggleIsAPanelFieldOnAPhoneOrOnceTheDraftChangedIt() {
        assertTrue(off.showsToggle(compact = true, applied = off))
        assertFalse(off.showsToggle(compact = false, applied = off))
        assertTrue(on.showsToggle(compact = false, applied = off))
    }

    @Test
    fun applyReportsOnlyAChangedToggle() {
        assertNull(off.toApply(applied = off))
        assertNull(on.toApply(applied = on))
        assertEquals(true, on.toApply(applied = off))
        assertEquals(false, off.toApply(applied = on))
    }

    @Test
    fun resetReturnsToTheDefault() {
        assertEquals(off, on.cleared())
        assertEquals(off, off.cleared())
    }

    private val byName = ListSortSelection(key = 0, ascending = true)
    private val byNameDesc = ListSortSelection(key = 0, ascending = false)
    private val byStatus = ListSortSelection(key = 5, ascending = true)

    @Test
    fun aNonDefaultSortCountsOnTopOfTheToggle() {
        assertEquals(0, ListTogglePanelFilter(on = false, sort = byName).activeCount(defaultSort = byName))
        assertEquals(1, ListTogglePanelFilter(on = false, sort = byNameDesc).activeCount(defaultSort = byName))
        assertEquals(1, ListTogglePanelFilter(on = false, sort = byStatus).activeCount(defaultSort = byName))
        assertEquals(2, ListTogglePanelFilter(on = true, sort = byStatus).activeCount(defaultSort = byName))
    }

    @Test
    fun aScreenWithoutSortNeverCountsOne() {
        assertEquals(1, ListTogglePanelFilter(on = true).activeCount(defaultSort = byName))
        assertEquals(0, ListTogglePanelFilter(on = false).activeCount(defaultSort = null))
    }

    @Test
    fun sortIsAPanelFieldOnAPhoneOrOnceTheDraftChangedIt() {
        val applied = ListTogglePanelFilter(on = false, sort = byName)
        assertTrue(applied.showsSort(compact = true, applied = applied))
        assertFalse(applied.showsSort(compact = false, applied = applied))
        assertTrue(applied.copy(sort = byStatus).showsSort(compact = false, applied = applied))
        assertFalse(off.showsSort(compact = true, applied = off))
    }

    @Test
    fun applyReportsOnlyAChangedSort() {
        val applied = ListTogglePanelFilter(on = false, sort = byName)
        assertNull(applied.sortToApply(applied))
        assertEquals(byNameDesc, applied.copy(sort = byNameDesc).sortToApply(applied))
        assertEquals(byStatus, applied.copy(sort = byStatus).sortToApply(applied))
        assertNull(off.sortToApply(off))
    }

    @Test
    fun resetReturnsToTheDefaultSortToo() {
        val drafted = ListTogglePanelFilter(on = true, sort = byStatus)
        assertEquals(ListTogglePanelFilter(on = false, sort = byName), drafted.cleared(defaultSort = byName))
    }

    @Test
    fun thePanelIsEmptyOnlyWhenNeitherFieldIsShown() {
        val applied = ListTogglePanelFilter(on = false, sort = byName)
        assertFalse(applied.isEmptyPanel(compact = true, applied = applied))
        assertTrue(applied.isEmptyPanel(compact = false, applied = applied))
        assertFalse(applied.copy(sort = byStatus).isEmptyPanel(compact = false, applied = applied))
        assertFalse(applied.copy(on = true).isEmptyPanel(compact = false, applied = applied))
        assertTrue(off.isEmptyPanel(compact = false, applied = off))
    }
}
