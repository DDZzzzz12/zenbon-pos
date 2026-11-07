package org.example.project.admin.features.voucher

import androidx.compose.ui.unit.dp
import org.example.project.admin.features.voucher.presentation.VoucherBarLayout
import org.example.project.ui.components.filter.PosFilterBarLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoucherBarLayoutTest {
    @Test
    fun phoneAlwaysPutsSearchInThePanel() {
        assertTrue(VoucherBarLayout.searchInPanel(compact = true, pageWidth = 1260.dp, screenPadding = 16.dp))
    }

    @Test
    fun wideColumnKeepsSearchInTheBar() {
        // 1500dp window minus a 240dp sidebar.
        assertFalse(VoucherBarLayout.searchInPanel(compact = false, pageWidth = 1260.dp, screenPadding = 32.dp))
    }

    @Test
    fun narrowMediumPageMovesSearchIntoThePanel() {
        // 768dp window minus the sidebar: search and the one-line stepper do not fit side by side.
        assertTrue(VoucherBarLayout.searchInPanel(compact = false, pageWidth = 548.dp, screenPadding = 24.dp))
    }

    @Test
    fun theBoundaryIsTheSharedBarMinimum() {
        val min = PosFilterBarLayout.minBarWidth(VoucherBarLayout.WIDE_SEARCH_MIN, VoucherBarLayout.WIDE_PERIOD_MIN)
        // 200 + 480 + 137 (measured Filter button) + 2 gaps of 8.
        assertEquals(833.dp, min)
        assertFalse(VoucherBarLayout.searchInPanel(compact = false, pageWidth = min + 64.dp, screenPadding = 32.dp))
        assertTrue(
            VoucherBarLayout.searchInPanel(compact = false, pageWidth = min + 63.dp, screenPadding = 32.dp),
        )
    }
}
