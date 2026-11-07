package org.example.project.admin.features.dashboard

import androidx.compose.ui.unit.dp
import org.example.project.admin.features.dashboard.presentation.components.dashboardTileColumns
import kotlin.test.Test
import kotlin.test.assertEquals

/** 148 dp minimum tile width, the layout's grid gap, clamped to 1…4 columns. */
class DashboardTileColumnsTest {
    @Test
    fun `reference widths give 2, 4 and 4 columns`() {
        assertEquals(2, dashboardTileColumns(358.dp, 8.dp)) // phone, 390 window
        assertEquals(4, dashboardTileColumns(640.dp, 12.dp)) // tablet, 768 window beside the rail
        assertEquals(4, dashboardTileColumns(752.dp, 16.dp)) // desktop left pane
    }

    @Test
    fun `a tile never gets narrower than 148 dp`() {
        // two tiles + one gap need exactly 2 * 148 + 8
        assertEquals(2, dashboardTileColumns(304.dp, 8.dp))
        assertEquals(1, dashboardTileColumns(303.dp, 8.dp))
    }

    @Test
    fun `clamps to one column on a very narrow pane`() {
        assertEquals(1, dashboardTileColumns(120.dp, 8.dp))
        assertEquals(1, dashboardTileColumns(0.dp, 8.dp))
    }

    @Test
    fun `clamps to four columns on a very wide pane`() {
        assertEquals(4, dashboardTileColumns(2000.dp, 16.dp))
    }
}
