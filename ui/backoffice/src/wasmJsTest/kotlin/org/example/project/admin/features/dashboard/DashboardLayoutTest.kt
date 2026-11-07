package org.example.project.admin.features.dashboard

import androidx.compose.ui.unit.dp
import org.example.project.admin.features.dashboard.presentation.DashboardLayout
import org.example.project.admin.features.dashboard.presentation.dashboardLayoutFor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The dashboard's three arrangements by content width: a status column from 760 dp, the two status
 * cards in a row from 560 dp (only when both are shown), stacked below that.
 */
class DashboardLayoutTest {
    @Test
    fun `from 760 dp the status cards get their own column`() {
        assertEquals(DashboardLayout.TwoPane, dashboardLayoutFor(760.dp, statusPairShown = true))
        assertEquals(DashboardLayout.TwoPane, dashboardLayoutFor(1200.dp, statusPairShown = false))
    }

    @Test
    fun `from 560 dp below 760 dp the two status cards share a row`() {
        assertEquals(DashboardLayout.StatusRow, dashboardLayoutFor(560.dp, statusPairShown = true))
        assertEquals(DashboardLayout.StatusRow, dashboardLayoutFor(759.dp, statusPairShown = true))
    }

    @Test
    fun `a single status card never takes a row of two`() {
        assertEquals(DashboardLayout.Stacked, dashboardLayoutFor(700.dp, statusPairShown = false))
    }

    @Test
    fun `below 560 dp everything stacks`() {
        assertEquals(DashboardLayout.Stacked, dashboardLayoutFor(559.dp, statusPairShown = true))
        assertEquals(DashboardLayout.Stacked, dashboardLayoutFor(358.dp, statusPairShown = true))
    }
}
