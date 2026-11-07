package org.example.project.ui.screens.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals

class LocationTabFontSizeTest {
    // A label of n characters needs n * size / 2 of width; the tab is 60 wide.
    private fun fits(
        label: String,
        size: Float,
    ) = label.length * size / 2 <= 60f

    @Test
    fun `every tab takes the size the longest label needs, not its own`() {
        val labels = listOf("Innen", "Außenbereich", "Abholung")
        // "Außenbereich" (12 chars) fits at 10; "Abholung" (8) alone would fit up to 15.
        assertEquals(10f, commonFitFontSize(labels, 14f, 6f, ::fits))
    }

    @Test
    fun `short labels keep the full size`() {
        assertEquals(14f, commonFitFontSize(listOf("Bar", "Eis"), 14f, 6f, ::fits))
    }

    @Test
    fun `it never goes below the minimum`() {
        assertEquals(6f, commonFitFontSize(listOf("x".repeat(100)), 14f, 6f, ::fits))
    }
}
