package org.example.project.ui.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaneSelectionTest {
    private val sections =
        listOf(
            PaneSection(id = "revenue", title = "Umsatz") {},
            PaneSection(id = "load", title = "Auslastung") {},
            PaneSection(id = "catalog", title = "Sortiment") {},
        )

    @Test
    fun defaultStyleIsChipsSoExistingHostsAreUnchanged() {
        assertEquals(PaneSelectorStyle.Chips, PaneSelectorStyle.Default)
    }

    @Test
    fun pageOfMapsAnIdToItsPagerPage() {
        assertEquals(0, PaneSelection.pageOf(sections, "revenue"))
        assertEquals(1, PaneSelection.pageOf(sections, "load"))
        assertEquals(2, PaneSelection.pageOf(sections, "catalog"))
    }

    @Test
    fun pageOfAnUnknownIdIsNull() {
        assertNull(PaneSelection.pageOf(sections, "missing"))
        assertNull(PaneSelection.pageOf(emptyList(), "revenue"))
    }

    @Test
    fun titleAtNamesTheCurrentSection() {
        assertEquals("Umsatz", PaneSelection.titleAt(sections, 0))
        assertEquals("Auslastung", PaneSelection.titleAt(sections, 1))
    }

    @Test
    fun titleAtClampsAPageOutsideTheList() {
        assertEquals("Umsatz", PaneSelection.titleAt(sections, -1))
        assertEquals("Sortiment", PaneSelection.titleAt(sections, 7))
    }

    @Test
    fun titleAtRefusesAnEmptyHost() {
        val error = assertFailsWith<IllegalArgumentException> { PaneSelection.titleAt(emptyList(), 0) }
        assertTrue(error.message.orEmpty().isNotEmpty())
    }

    @Test
    fun onlySeveralSectionsNeedASelector() {
        assertFalse(PaneSelection.needsSelector(0))
        assertFalse(PaneSelection.needsSelector(1))
        assertTrue(PaneSelection.needsSelector(2))
    }
}
