package org.example.project.ui.components.floorplan

import androidx.compose.ui.geometry.Offset
import org.example.project.core.utils.GridCellDimensions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FloorPlanViewportTest {
    @Test
    fun `zoom is clamped to 1 and 5`() {
        assertEquals(1f, FloorPlanViewport.zoomedBy(1f, 0.5f))
        assertEquals(5f, FloorPlanViewport.zoomedBy(4.5f, 2f))
    }

    @Test
    fun `pan is bounded by the overhang`() {
        val p = FloorPlanViewport.clampPan(Offset(1000f, -1000f), zoom = 2f, paneWidth = 400f, paneHeight = 600f)
        assertEquals(Offset(200f, -300f), p)
    }

    @Test
    fun `zoom 1 allows no pan`() = assertEquals(Offset.Zero, FloorPlanViewport.clampPan(Offset(50f, 50f), 1f, 400f, 600f))

    @Test
    fun `pan scales with the zoom and is re-clamped`() {
        val p = FloorPlanViewport.panAfterZoom(Offset(100f, 0f), oldZoom = 2f, newZoom = 1f, paneWidth = 400f, paneHeight = 600f)
        assertEquals(Offset.Zero, p)
    }

    @Test
    fun `wheel direction`() {
        assertEquals(FloorPlanViewport.STEP, FloorPlanViewport.wheelFactor(-100f))
        assertEquals(1f / FloorPlanViewport.STEP, FloorPlanViewport.wheelFactor(100f))
        assertEquals(1f, FloorPlanViewport.wheelFactor(0f))
    }

    private val cells = GridCellDimensions(cellWidth = 10f, cellHeight = 10f)

    private fun assertNear(
        expected: Float,
        actual: Float,
    ) = assertTrue(kotlin.math.abs(expected - actual) < 0.01f, "expected $expected but was $actual")

    private val grid = FloorPlanContentBounds.GRID

    @Test
    fun `a pane that fits the grid exactly fits at 1 and zero pan`() {
        assertEquals(FloorPlanViewport.View(1f, Offset.Zero), FloorPlanViewport.fit(grid, 800f, 550f, cells))
        // Unclamped cells: pane / (80 * (pane / 80)) is float noise around 1.
        val c = GridCellDimensions(cellWidth = 1000f / 80f, cellHeight = 700f / 55f)
        assertEquals(FloorPlanViewport.View(1f, Offset.Zero), FloorPlanViewport.fit(grid, 1000f, 700f, c))
        assertEquals(FloorPlanViewport.View(1f, Offset.Zero), FloorPlanViewport.fit(grid, 0f, 0f, cells))
    }

    @Test
    fun `a pane smaller than the grid at 10px minimum cells zooms out to show it whole`() {
        // 390 px wide: cells are clamped to 10 px, so the plan is 800 px wide.
        val c = GridCellDimensions(cellWidth = 10f, cellHeight = 600f / 55f)
        val v = FloorPlanViewport.fit(grid, 390f, 600f, c, minZoom = 0.1f)
        assertNear(390f / 800f, v.zoom)
        // grid centre (400, 300) lands on the pane centre (195, 300)
        assertNear(195f, 195f + v.zoom * (400f - 195f) + v.pan.x)
        assertNear(0f, v.pan.y)
    }

    @Test
    fun `bounds beyond the grid zoom out with a margin and centre`() {
        val b = FloorPlanContentBounds(0f, 0f, 362f, 55f)
        val v = FloorPlanViewport.fit(b, 800f, 550f, cells, minZoom = 0.1f)
        assertNear(760f / 3620f, v.zoom)
        assertNear(400f, 400f + v.zoom * (1810f - 400f) + v.pan.x)
        assertNear(0f, v.pan.y)
    }

    @Test
    fun `fit never goes below the minimum zoom`() {
        val b = FloorPlanContentBounds(0f, 0f, 5000f, 55f)
        assertEquals(0.1f, FloorPlanViewport.fit(b, 800f, 550f, cells, minZoom = 0.1f).zoom)
        assertEquals(1f, FloorPlanViewport.fit(b, 800f, 550f, cells).zoom)
    }

    @Test
    fun `clampPan below 1x does not throw and honours overscroll`() {
        assertEquals(Offset.Zero, FloorPlanViewport.clampPan(Offset(50f, -50f), 0.5f, 400f, 600f))
        assertEquals(Offset(200f, -300f), FloorPlanViewport.clampPan(Offset(1000f, -1000f), 0.5f, 400f, 600f, overscroll = 0.5f))
        // above 1x the overhang adds to the overscroll
        assertEquals(Offset(400f, 0f), FloorPlanViewport.clampPan(Offset(1000f, 0f), 2f, 400f, 600f, overscroll = 0.5f))
    }

    @Test
    fun `custom minimum zoom is honoured`() {
        assertEquals(0.1f, FloorPlanViewport.zoomedBy(0.2f, 0.1f, minZoom = 0.1f))
        assertEquals(1f, FloorPlanViewport.zoomedBy(1f, 0.5f))
        val p = FloorPlanViewport.panAfterZoom(Offset(100f, 0f), 1f, 0.5f, 400f, 600f, overscroll = 0.5f)
        assertEquals(Offset(50f, 0f), p)
    }

    @Test
    fun `a small plan is framed by zooming in up to maxZoom and centred`() {
        // 20 x 10 cells of 10 px in an 800 x 550 pane: fill 0.95 -> min(3.8, 5.225) -> capped at 2.
        val b = FloorPlanContentBounds(10f, 5f, 30f, 15f)
        val v = FloorPlanViewport.fit(b, 800f, 550f, cells, minZoom = 0.1f, maxZoom = 2f)
        assertNear(2f, v.zoom)
        // the content centre (200, 100) lands on the pane centre (400, 275)
        assertNear(400f, 400f + v.zoom * (200f - 400f) + v.pan.x)
        assertNear(275f, 275f + v.zoom * (100f - 275f) + v.pan.y)
        // the till's default still never zooms in
        assertEquals(1f, FloorPlanViewport.fit(b, 800f, 550f, cells).zoom)
    }

    @Test
    fun `content bounds are what the plan draws`() {
        fun table(
            x: Int?,
            y: Int?,
            w: Int?,
            h: Int?,
        ) = org.example.project.core.data.models.RTable(
            id = 1,
            tableNumber = "T",
            seats = 4,
            locationTypeId = 1,
            locationTypeName = "",
            tableStatus = "FREE",
            currentSessionId = null,
            gridX = x,
            gridY = y,
            rotation = null,
            gridWidth = w,
            gridHeight = h,
        )
        assertEquals(null, FloorPlanContentBounds.of(emptyList(), emptyList()))
        // a pixel-like position (x 220) is drawn pulled back onto the grid with its size kept, and a
        // 1-cell span is floored at 2 cells
        val b = FloorPlanContentBounds.of(listOf(table(220, 3, 9, 1), table(4, 2, 2, 3)), emptyList())
        assertEquals(FloorPlanContentBounds(4f, 2f, 80f, 5f), b)
    }

    @Test
    fun aPanFollowsThePointerOneToOneAtAnyZoom() {
        // The change is in screen pixels and so is the layer's translation: at 4x the plan must still move
        // exactly as far as the pointer, not four times as far.
        listOf(1f, 2f, 4f).forEach { zoom ->
            val pan =
                FloorPlanViewport.pannedBy(
                    pan = Offset.Zero,
                    panChange = Offset(30f, -12f),
                    zoom = zoom,
                    paneWidth = 1000f,
                    paneHeight = 600f,
                    overscroll = 0.5f,
                )
            assertEquals(Offset(30f, -12f), pan, "zoom $zoom")
        }
    }

    @Test
    fun aPanIsStillBoundedByTheOverhang() {
        val pan =
            FloorPlanViewport.pannedBy(
                pan = Offset(400f, 0f),
                panChange = Offset(500f, 0f),
                zoom = 2f,
                paneWidth = 1000f,
                paneHeight = 600f,
            )
        assertEquals(Offset(500f, 0f), pan)
    }
}
