package org.example.project.ui.screens.settings

import org.example.project.ui.layout.PaneSection
import kotlin.test.Test
import kotlin.test.assertEquals

class ExportSectionSelectionTest {
    private val sections = listOf("a", "b", "c").map { PaneSection(it, it.uppercase()) {} }

    @Test
    fun `the selected id wins`() {
        assertEquals("b", selectedSectionOrFirst(sections, "b").id)
    }

    @Test
    fun `no selection defaults to the first section`() {
        assertEquals("a", selectedSectionOrFirst(sections, null).id)
    }

    @Test
    fun `a selected id that disappeared falls back to the first section`() {
        assertEquals("a", selectedSectionOrFirst(sections.filter { it.id != "c" }, "c").id)
    }
}
