package org.example.project.ui.components.beleg

import kotlin.test.Test
import kotlin.test.assertEquals

class BelegFlowStripLayoutTest {
    @Test
    fun layoutFollowsTheWidthItIsGiven() {
        assertEquals(BelegFlowStripLayout.Compact, belegFlowStripLayoutFor(390))
        assertEquals(BelegFlowStripLayout.Compact, belegFlowStripLayoutFor(599))
        assertEquals(BelegFlowStripLayout.Medium, belegFlowStripLayoutFor(600))
        assertEquals(BelegFlowStripLayout.Medium, belegFlowStripLayoutFor(839))
        assertEquals(BelegFlowStripLayout.Large, belegFlowStripLayoutFor(840))
        assertEquals(BelegFlowStripLayout.Large, belegFlowStripLayoutFor(1440))
    }
}
