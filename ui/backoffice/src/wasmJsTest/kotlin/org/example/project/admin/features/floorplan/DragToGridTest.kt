package org.example.project.admin.features.floorplan

import org.example.project.admin.features.floorplan.presentation.components.dragToGrid
import org.example.project.core.utils.GridCellDimensions
import kotlin.test.Test
import kotlin.test.assertEquals

class DragToGridTest {
    private val cells = GridCellDimensions(cellWidth = 20f, cellHeight = 10f)

    @Test
    fun aDragOfTwoCellsMovesTwoCells() {
        val (gx, gy) =
            dragToGrid(
                startGridX = 5,
                startGridY = 5,
                dragX = 40f,
                dragY = 20f,
                cells = cells,
            )
        assertEquals(7, gx)
        assertEquals(7, gy)
    }

    @Test
    fun aDragBeyondTheGridIsClampedToTheLastCell() {
        val (gx, gy) =
            dragToGrid(
                startGridX = 78,
                startGridY = 53,
                dragX = 10_000f,
                dragY = 10_000f,
                cells = cells,
            )
        assertEquals(79, gx)
        assertEquals(54, gy)
    }

    @Test
    fun aDragBeforeTheOriginIsClampedToZero() {
        val (gx, gy) =
            dragToGrid(
                startGridX = 1,
                startGridY = 1,
                dragX = -10_000f,
                dragY = -10_000f,
                cells = cells,
            )
        assertEquals(0, gx)
        assertEquals(0, gy)
    }

    @Test
    fun aTableStoredOffTheGridDragsFromTheCellItIsDrawnIn() {
        // Stored at column 360 but drawn clamped at column 79; 80 plan px left is four 20 px cells.
        val (gx, gy) =
            dragToGrid(startGridX = 360, startGridY = 10, dragX = -80f, dragY = 0f, cells = cells)
        assertEquals(75, gx)
        assertEquals(10, gy)
    }

    @Test
    fun aSizedTableKeepsItsWholeFootprintOnTheGrid() {
        // An 8-cell table stored at column 220 (pixel-like) is drawn at 72..80; dragging it right
        // cannot push it past the edge, and dragging it left starts from where it is drawn.
        val (gxRight, _) =
            dragToGrid(startGridX = 220, startGridY = 0, dragX = 400f, dragY = 0f, cells = cells, wCells = 8, hCells = 8)
        assertEquals(72, gxRight)
        val (gxLeft, _) =
            dragToGrid(startGridX = 220, startGridY = 0, dragX = -80f, dragY = 0f, cells = cells, wCells = 8, hCells = 8)
        assertEquals(68, gxLeft)
    }

    @Test
    fun theDragIsInPlanPixelsSoItDoesNotDependOnTheZoom() {
        // detectDragGestures inside the scaled layer reports plan pixels already (40 px = two 20 px cells),
        // whatever the zoom; the table must land where the live offset already drew it.
        val (gx, gy) = dragToGrid(startGridX = 5, startGridY = 5, dragX = 40f, dragY = 20f, cells = cells)
        assertEquals(7, gx)
        assertEquals(7, gy)
    }
}
