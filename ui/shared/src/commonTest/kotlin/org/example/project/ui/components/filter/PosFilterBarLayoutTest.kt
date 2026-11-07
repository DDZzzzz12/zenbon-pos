package org.example.project.ui.components.filter

import androidx.compose.ui.unit.dp
import org.example.project.ui.theme.PosSpacing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PosFilterBarLayoutTest {
    @Test
    fun buttonWidthIsTheMeasuredTwoDigitLabel() {
        // "Filter (88)" measured 136.5dp in the browser (DE/EN; ZH "筛选 (88)" 130dp), rounded up.
        assertEquals(137.dp, PosFilterBarLayout.FILTER_BUTTON_WIDTH)
        assertEquals(PosSpacing.md, PosFilterBarLayout.GAP)
    }

    @Test
    fun minBarWidthIsSearchPeriodButtonAndTwoGaps() {
        assertEquals(
            200.dp + 480.dp + 137.dp + PosSpacing.md * 2,
            PosFilterBarLayout.minBarWidth(searchMin = 200.dp, periodMin = 480.dp),
        )
    }

    @Test
    fun withoutAFilterButtonOnlyOneGapRemains() {
        assertEquals(
            200.dp + 480.dp + PosSpacing.md,
            PosFilterBarLayout.minBarWidth(searchMin = 200.dp, periodMin = 480.dp, hasFilterButton = false),
        )
    }

    @Test
    fun fitsExactlyAtTheMinimumAndNotOneDpBelow() {
        val min = PosFilterBarLayout.minBarWidth(searchMin = 240.dp, periodMin = 580.dp)
        assertTrue(PosFilterBarLayout.fitsInOneRow(min, searchMin = 240.dp, periodMin = 580.dp))
        assertFalse(PosFilterBarLayout.fitsInOneRow(min - 1.dp, searchMin = 240.dp, periodMin = 580.dp))
    }

    @Test
    fun periodMaxWidthIsWhatSearchAndTheButtonLeave() {
        assertEquals(
            1000.dp - 240.dp - 137.dp - PosSpacing.md * 2,
            PosFilterBarLayout.periodMaxWidth(1000.dp, searchMin = 240.dp),
        )
    }

    @Test
    fun anEmptySearchSlotStillCostsItsGap() {
        // PosFilterBar always composes the weighted search slot, so its gap stays even when empty.
        assertEquals(
            1000.dp - 137.dp - PosSpacing.md * 2,
            PosFilterBarLayout.periodMaxWidth(1000.dp, searchMin = 0.dp),
        )
        assertEquals(
            1000.dp - PosSpacing.md,
            PosFilterBarLayout.periodMaxWidth(1000.dp, searchMin = 0.dp, hasFilterButton = false),
        )
    }

    @Test
    fun periodMaxWidthNeverGoesNegative() {
        assertEquals(0.dp, PosFilterBarLayout.periodMaxWidth(100.dp, searchMin = 240.dp))
    }
}
