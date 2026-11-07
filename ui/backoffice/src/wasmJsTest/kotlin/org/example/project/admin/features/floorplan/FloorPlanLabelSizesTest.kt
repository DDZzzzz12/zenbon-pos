package org.example.project.admin.features.floorplan

import androidx.compose.ui.unit.dp
import org.example.project.admin.features.floorplan.presentation.components.labelSizes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The design: 16px number and 11px seats line on a 24px cell. */
class FloorPlanLabelSizesTest {
    @Test
    fun `sizes are the design's proportions of the cell`() {
        val sizes = labelSizes(cell = 24.dp, width = 72.dp, numberLength = 1, seatsLength = 8)
        assertEquals(16.dp.value, sizes.number.value, 0.01f)
        assertEquals(11.dp.value, sizes.seats.value, 0.01f)
    }

    @Test
    fun `sizes scale with the cell`() {
        val small = labelSizes(cell = 8.dp, width = 24.dp, numberLength = 1, seatsLength = 8)
        val big = labelSizes(cell = 24.dp, width = 72.dp, numberLength = 1, seatsLength = 8)
        assertEquals(big.number.value / 3f, small.number.value, 0.01f)
    }

    @Test
    fun `a long name is limited by the card width`() {
        val short = labelSizes(cell = 24.dp, width = 48.dp, numberLength = 1, seatsLength = 0)
        val long = labelSizes(cell = 24.dp, width = 48.dp, numberLength = 8, seatsLength = 0)
        assertTrue(long.number < short.number)
    }

    @Test
    fun `a zero-size card never gives a zero font size`() {
        val sizes = labelSizes(cell = 0.dp, width = 0.dp, numberLength = 3, seatsLength = 8)
        assertTrue(sizes.number >= 2.dp)
        assertTrue(sizes.seats >= 2.dp)
    }
}
