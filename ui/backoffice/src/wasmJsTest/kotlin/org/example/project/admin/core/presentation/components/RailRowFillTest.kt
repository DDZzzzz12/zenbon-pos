package org.example.project.admin.core.presentation.components

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RailRowFillTest {
    private val selectedFill = Color(0xFFD0E4FF)
    private val idleFill = Color(0xFFE0E2EC)

    private fun fill(
        selected: Boolean,
        hovered: Boolean,
    ) = railRowFill(selected, hovered, selectedFill, idleFill)

    private fun distance(
        a: Color,
        b: Color,
    ) = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)

    @Test
    fun `selected fill differs from unselected fill`() = assertNotEquals(fill(selected = false, hovered = false), fill(selected = true, hovered = false))

    @Test
    fun `selected row ignores hover`() = assertEquals(fill(selected = true, hovered = false), fill(selected = true, hovered = true))

    @Test
    fun `hover tints an unselected row`() = assertNotEquals(fill(selected = false, hovered = false), fill(selected = false, hovered = true))

    @Test
    fun `hover is never stronger than selected`() {
        val hover = fill(selected = false, hovered = true)
        assertTrue(distance(idleFill, hover) < distance(idleFill, selectedFill))
        assertNotEquals(selectedFill, hover)
    }
}
