package org.example.project.admin.features.kassenbuch

import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import org.example.project.admin.features.kassenbuch.presentation.KassenbuchBarLayout
import org.example.project.admin.features.kassenbuch.presentation.KassenbuchPanelFilter
import org.example.project.admin.features.kassenbuch.presentation.KassenbuchState
import org.example.project.admin.features.kassenbuch.presentation.visibleCashBooks
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.features.reports.presentation.ReportsGranularity
import org.example.project.ui.components.filter.PosFilterBarLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KassenbuchPanelFilterTest {
    private val applied = KassenbuchPanelFilter(granularity = ReportsGranularity.MONTH, searchTerm = "")

    @Test
    fun ofReadsGranularityAndSearchFromTheState() {
        val state =
            KassenbuchState(anchor = LocalDate(2026, 10, 21), granularity = ReportsGranularity.WEEK, filterText = "Miete")
        assertEquals(KassenbuchPanelFilter(ReportsGranularity.WEEK, "Miete"), KassenbuchPanelFilter.of(state))
    }

    @Test
    fun nothingAppliedCountsZero() {
        assertEquals(0, applied.activeCount(searchInPanel = true))
    }

    @Test
    fun searchCountsOnlyWhereThePanelHoldsIt() {
        val searching = applied.copy(searchTerm = "Miete")
        assertEquals(1, searching.activeCount(searchInPanel = true))
        // In the bar the search field shows itself; it is not a panel filter.
        assertEquals(0, searching.activeCount(searchInPanel = false))
        assertEquals(0, applied.copy(searchTerm = "   ").activeCount(searchInPanel = true))
    }

    @Test
    fun theGranularityIsNeverCountedBecauseThePeriodIsAlwaysVisible() {
        assertEquals(0, applied.copy(granularity = ReportsGranularity.YEAR).activeCount(searchInPanel = true))
    }

    @Test
    fun searchIsAPanelFieldWhereTheBarHasNoRoomOrOnceTheDraftChangedIt() {
        assertTrue(applied.showsSearch(searchInPanel = true, applied = applied))
        assertFalse(applied.showsSearch(searchInPanel = false, applied = applied))
        assertTrue(applied.copy(searchTerm = "x").showsSearch(searchInPanel = false, applied = applied))
    }

    @Test
    fun granularityIsAPanelFieldOnAPhoneOrOnceTheDraftChangedIt() {
        assertTrue(applied.showsGranularity(compact = true, applied = applied))
        assertFalse(applied.showsGranularity(compact = false, applied = applied))
        assertTrue(applied.copy(granularity = ReportsGranularity.DAY).showsGranularity(compact = false, applied = applied))
    }

    @Test
    fun applyOnlyCarriesWhatTheDraftChanged() {
        assertNull(applied.granularityToApply(applied))
        assertNull(applied.searchToApply(applied))
        val draft = applied.copy(granularity = ReportsGranularity.YEAR, searchTerm = "Bank")
        assertEquals(ReportsGranularity.YEAR, draft.granularityToApply(applied))
        assertEquals("Bank", draft.searchToApply(applied))
        // Clearing a search is a change too.
        assertEquals("", applied.searchToApply(applied.copy(searchTerm = "Bank")))
    }

    @Test
    fun resetClearsSearchOnlyWhereThePanelShowsItAndKeepsThePeriod() {
        val draft = KassenbuchPanelFilter(ReportsGranularity.DAY, "Bank")
        assertEquals(KassenbuchPanelFilter(ReportsGranularity.DAY, ""), draft.cleared(clearSearch = true))
        assertEquals(draft, draft.cleared(clearSearch = false))
    }

    @Test
    fun searchAlwaysMovesIntoThePanelOnAPhone() {
        assertTrue(KassenbuchBarLayout.searchInPanel(compact = true, barWidth = 2000.dp))
    }

    @Test
    fun searchStaysInTheBarWhileItFitsBesideTheFullPeriodStepper() {
        val oneRow = KassenbuchBarLayout.WIDE_SEARCH_MIN + PosFilterBarLayout.GAP + KassenbuchBarLayout.WIDE_PERIOD_MIN
        assertFalse(KassenbuchBarLayout.searchInPanel(compact = false, barWidth = oneRow))
        assertTrue(KassenbuchBarLayout.searchInPanel(compact = false, barWidth = oneRow - 1.dp))
    }

    @Test
    fun theWidePeriodStepperGetsWhatSearchAndTheFilterButtonLeave() {
        // Search in the bar: no panel, so no Filter button either.
        val gap = PosFilterBarLayout.GAP
        assertEquals(700.dp - 200.dp - gap, KassenbuchBarLayout.periodMaxWidth(barWidth = 700.dp, searchInPanel = false))
        // Search in the panel: the Filter button is the only other control.
        assertEquals(
            700.dp - gap - PosFilterBarLayout.FILTER_BUTTON_WIDTH - gap,
            KassenbuchBarLayout.periodMaxWidth(barWidth = 700.dp, searchInPanel = true),
        )
    }

    @Test
    fun theBookSwitchOffersActiveBooksInSortOrderThenByName() {
        val books =
            listOf(
                book(1, "Tresor", sortOrder = 2),
                book(2, "Ladenkasse", sortOrder = 1),
                book(3, "Alt", sortOrder = 0, active = false),
                book(4, "Bank", sortOrder = 2),
            )
        assertEquals(listOf(2L, 4L, 1L), visibleCashBooks(books).map { it.id })
    }

    private fun book(
        id: Long,
        name: String,
        sortOrder: Int,
        active: Boolean = true,
    ) = CashBookDto.ResponseDto(
        id = id,
        name = name,
        isFiscal = false,
        datevAccount = "1000",
        closeInterval = CashBookCloseInterval.MONTHLY,
        isActive = active,
        sortOrder = sortOrder,
        currentBalance = "0.00",
        currency = "EUR",
    )
}
