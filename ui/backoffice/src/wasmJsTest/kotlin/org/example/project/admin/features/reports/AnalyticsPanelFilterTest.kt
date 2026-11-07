package org.example.project.admin.features.reports

import kotlinx.datetime.LocalDate
import org.example.project.admin.features.reports.presentation.AnalyticsPanelFilter
import org.example.project.admin.features.reports.presentation.ReportsState
import org.example.project.features.reports.presentation.ReportsGranularity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Analysen filter panel: employee and Vergleich (and the granularity on a phone) are a draft,
 * applied only by the panel button; the badge counts only what differs from the default.
 */
class AnalyticsPanelFilterTest {
    private val applied = AnalyticsPanelFilter(ReportsGranularity.DAY, employeeId = null, compareEnabled = false)
    private val today = LocalDate(2026, 10, 21)

    @Test
    fun ofReadsTheAppliedFilterFromTheState() {
        val state =
            ReportsState(
                anchor = today,
                granularity = ReportsGranularity.MONTH,
                compareEnabled = true,
                employeeId = "e-1",
            )
        assertEquals(AnalyticsPanelFilter(ReportsGranularity.MONTH, "e-1", compareEnabled = true), AnalyticsPanelFilter.of(state))
    }

    @Test
    fun nothingSetCountsZero() {
        assertEquals(0, applied.activeCount())
    }

    @Test
    fun theGranularityIsNeverCounted() {
        assertEquals(0, applied.copy(granularity = ReportsGranularity.YEAR).activeCount())
    }

    @Test
    fun employeeAndCompareCountOneEach() {
        assertEquals(1, applied.copy(employeeId = "e-1").activeCount())
        assertEquals(1, applied.copy(compareEnabled = true).activeCount())
        assertEquals(2, applied.copy(employeeId = "e-1", compareEnabled = true).activeCount())
    }

    @Test
    fun granularityShowsOnAPhoneOrOnceTheDraftChangedIt() {
        assertTrue(applied.showsGranularity(compact = true, applied = applied))
        assertFalse(applied.showsGranularity(compact = false, applied = applied))
        assertTrue(applied.copy(granularity = ReportsGranularity.WEEK).showsGranularity(compact = false, applied = applied))
    }

    @Test
    fun resetClearsEmployeeAndCompareButKeepsThePeriod() {
        val draft = AnalyticsPanelFilter(ReportsGranularity.MONTH, "e-1", compareEnabled = true)
        assertEquals(AnalyticsPanelFilter(ReportsGranularity.MONTH, null, compareEnabled = false), draft.cleared())
    }

    @Test
    fun anUnchangedDraftNeitherReloadsNorFlipsCompare() {
        assertFalse(applied.needsReload(applied))
        assertNull(applied.compareToSet(applied))
    }

    @Test
    fun aNewEmployeeOrGranularityReloads() {
        assertTrue(applied.copy(employeeId = "e-1").needsReload(applied))
        assertTrue(applied.copy(granularity = ReportsGranularity.MONTH).needsReload(applied))
    }

    @Test
    fun onlyCompareChangedFlipsCompareWithoutASeparateReload() {
        val draft = applied.copy(compareEnabled = true)
        assertFalse(draft.needsReload(applied))
        assertEquals(true, draft.compareToSet(applied))
    }

    @Test
    fun aGranularitySwitchClampsTheAnchorToToday() {
        val future = LocalDate(2026, 12, 1)
        assertEquals(today, applied.copy(granularity = ReportsGranularity.MONTH).anchorFor(applied, future, today))
    }

    @Test
    fun theSameGranularityKeepsTheAnchor() {
        val past = LocalDate(2026, 9, 3)
        assertEquals(past, applied.copy(employeeId = "e-1").anchorFor(applied, past, today))
    }
}
