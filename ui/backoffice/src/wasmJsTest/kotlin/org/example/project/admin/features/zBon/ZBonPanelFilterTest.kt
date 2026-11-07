package org.example.project.admin.features.zBon

import kotlinx.datetime.LocalDate
import org.example.project.admin.features.zBon.presentation.ZBonListState
import org.example.project.admin.features.zBon.presentation.ZBonPanelFilter
import org.example.project.features.reports.presentation.ReportsGranularity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZBonPanelFilterTest {
    private val applied = ZBonPanelFilter(ReportsGranularity.MONTH)

    @Test
    fun ofReadsTheGranularityFromTheState() {
        val state = ZBonListState(anchor = LocalDate(2026, 10, 21), granularity = ReportsGranularity.YEAR)
        assertEquals(ZBonPanelFilter(ReportsGranularity.YEAR), ZBonPanelFilter.of(state))
    }

    @Test
    fun theGranularityIsNeverCountedBecauseThePeriodIsAlwaysVisible() {
        assertEquals(0, ZBonPanelFilter(ReportsGranularity.DAY).activeCount())
    }

    @Test
    fun granularityIsAPanelFieldOnAPhoneOrOnceTheDraftChangedIt() {
        assertTrue(applied.showsGranularity(compact = true, applied = applied))
        assertFalse(applied.showsGranularity(compact = false, applied = applied))
        assertTrue(applied.copy(granularity = ReportsGranularity.WEEK).showsGranularity(compact = false, applied = applied))
    }

    @Test
    fun applyLoadsOnlyAChangedGranularity() {
        assertNull(applied.granularityToApply(applied))
        assertEquals(ReportsGranularity.DAY, applied.copy(granularity = ReportsGranularity.DAY).granularityToApply(applied))
    }
}
