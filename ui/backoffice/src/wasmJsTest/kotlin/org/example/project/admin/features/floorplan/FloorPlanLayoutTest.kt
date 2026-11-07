package org.example.project.admin.features.floorplan

import androidx.compose.ui.unit.dp
import org.example.project.admin.features.floorplan.presentation.FloorPlanLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class FloorPlanLayoutTest {
    @Test
    fun `wide from 900dp, medium from 560dp, compact below`() {
        assertEquals(FloorPlanLayout.WIDE, FloorPlanLayout.forWidth(900.dp))
        assertEquals(FloorPlanLayout.MEDIUM, FloorPlanLayout.forWidth(899.dp))
        assertEquals(FloorPlanLayout.MEDIUM, FloorPlanLayout.forWidth(560.dp))
        assertEquals(FloorPlanLayout.COMPACT, FloorPlanLayout.forWidth(559.dp))
    }

    @Test
    fun `the stacked layouts have a fixed canvas height, the wide one fills`() {
        assertEquals(360.dp, FloorPlanLayout.MEDIUM.canvasHeight)
        assertEquals(300.dp, FloorPlanLayout.COMPACT.canvasHeight)
        assertEquals(null, FloorPlanLayout.WIDE.canvasHeight)
    }
}
