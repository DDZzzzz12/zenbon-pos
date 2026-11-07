package org.example.project.admin.features.voucher

import org.example.project.admin.features.voucher.presentation.VoucherPanelFilter
import org.example.project.features.reports.presentation.ReportsGranularity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The draft must survive a resize that moves search between the bar and the panel while the panel
 * is open: a search typed into the panel stays visible there and is applied, never silently dropped.
 */
class VoucherPanelFilterTest {
    private val applied = VoucherPanelFilter(ReportsGranularity.MONTH, employeeId = null, searchTerm = "")

    @Test
    fun searchShowsInThePanelWhereTheBarHasNoRoom() {
        assertTrue(applied.showsSearch(searchInPanel = true, applied = applied))
        assertFalse(applied.showsSearch(searchInPanel = false, applied = applied))
    }

    @Test
    fun aSearchTypedInThePanelStaysShownWhenTheWindowGrows() {
        val draft = applied.copy(searchTerm = "GS-1")
        assertTrue(draft.showsSearch(searchInPanel = false, applied = applied))
    }

    @Test
    fun aSearchTypedInThePanelIsAppliedWhereverSearchNowSits() {
        assertEquals("GS-1", applied.copy(searchTerm = "GS-1").searchToApply(applied))
        assertNull(applied.searchToApply(applied))
    }

    @Test
    fun granularityShowsOnAPhoneOrOnceTheDraftChangedIt() {
        assertTrue(applied.showsGranularity(compact = true, applied = applied))
        assertFalse(applied.showsGranularity(compact = false, applied = applied))
        assertTrue(applied.copy(granularity = ReportsGranularity.YEAR).showsGranularity(compact = false, applied = applied))
    }

    @Test
    fun clearingDropsTheEmployeeAndOnlyAShownSearch() {
        val draft = VoucherPanelFilter(ReportsGranularity.MONTH, employeeId = "e1", searchTerm = "GS")
        assertEquals(draft.copy(employeeId = null, searchTerm = ""), draft.cleared(clearSearch = true))
        assertEquals(draft.copy(employeeId = null), draft.cleared(clearSearch = false))
    }
}
