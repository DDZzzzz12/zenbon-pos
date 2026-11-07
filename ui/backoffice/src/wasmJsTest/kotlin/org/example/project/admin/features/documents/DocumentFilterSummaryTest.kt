package org.example.project.admin.features.documents

import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import org.example.project.admin.features.documents.presentation.DocumentBarLayout
import org.example.project.admin.features.documents.presentation.DocumentFilterSummary
import org.example.project.admin.features.documents.presentation.DocumentSearchFilter
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.features.reports.presentation.ReportsGranularity
import org.example.project.ui.components.filter.PosFilterBarLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentFilterSummaryTest {
    private val base = DocumentSearchFilter(anchor = LocalDate(2026, 10, 21))

    private val busy =
        base.copy(
            granularity = ReportsGranularity.YEAR,
            searchTerm = "metro",
            fileType = "INVOICE",
            includeInactive = true,
            vendorName = "Metro",
            minAmount = "5.00",
            processingStatuses = setOf(ProcessingStatus.REVIEWED, ProcessingStatus.FAILED),
        )

    @Test
    fun nothingAppliedCountsZero() {
        assertEquals(0, DocumentFilterSummary.activeCount(base, typeInPanel = true))
    }

    @Test
    fun countsEachAdvancedFieldEachStatusAndInactive() {
        // 2 advanced + 2 statuses + inactive; type is a quick chip in the bar, period never counts.
        assertEquals(5, DocumentFilterSummary.activeCount(busy, typeInPanel = false))
    }

    @Test
    fun typeCountsOnlyWhereThePanelHoldsIt() {
        assertEquals(6, DocumentFilterSummary.activeCount(busy, typeInPanel = true))
    }

    @Test
    fun clearedKeepsSearchAndPeriod() {
        val cleared = DocumentFilterSummary.cleared(busy, clearType = true)
        assertEquals(base.copy(granularity = ReportsGranularity.YEAR, searchTerm = "metro"), cleared)
    }

    @Test
    fun clearedKeepsTheTypeWhenItIsNotAPanelField() {
        assertEquals("INVOICE", DocumentFilterSummary.cleared(busy, clearType = false).fileType)
    }

    @Test
    fun phoneAlwaysPutsThePeriodInThePanel() {
        assertTrue(DocumentBarLayout.periodInPanel(compact = true, barWidth = 1200.dp))
    }

    @Test
    fun wideBarKeepsSearchPeriodAndFilterInOneRow() {
        assertFalse(DocumentBarLayout.periodInPanel(compact = false, barWidth = 1200.dp))
    }

    @Test
    fun narrowMediumBarMovesThePeriodIntoThePanel() {
        assertTrue(DocumentBarLayout.periodInPanel(compact = false, barWidth = 520.dp))
    }

    @Test
    fun aBarThatWouldWrapTheStepperMovesThePeriodIntoThePanel() {
        // A 1090px window with the sidebar leaves an ~800dp bar: the stepper wrapped to two lines there.
        assertTrue(DocumentBarLayout.periodInPanel(compact = false, barWidth = 800.dp))
        assertTrue(DocumentBarLayout.periodInPanel(compact = false, barWidth = 960.dp))
        // A 1500px window: one line with room for the widest (custom range) label.
        assertFalse(DocumentBarLayout.periodInPanel(compact = false, barWidth = 1210.dp))
    }

    @Test
    fun periodWidthIsWhatSearchAndTheButtonLeave() {
        val width = 1000.dp
        assertEquals(PosFilterBarLayout.periodMaxWidth(width, DocumentBarLayout.SEARCH_MIN), DocumentBarLayout.periodMaxWidth(width))
        // 240 search + 580 period + 137 measured Filter button + 2 gaps of 8.
        assertEquals(973.dp, DocumentBarLayout.minBarWidthForPeriod())
        assertFalse(DocumentBarLayout.periodInPanel(compact = false, barWidth = DocumentBarLayout.minBarWidthForPeriod()))
        assertTrue(DocumentBarLayout.periodInPanel(compact = false, barWidth = DocumentBarLayout.minBarWidthForPeriod() - 1.dp))
    }

    @Test
    fun aPeriodTheDraftChangedStaysShownInThePanel() {
        assertFalse(DocumentFilterSummary.showsPeriod(periodInPanel = false, applied = base, draft = base))
        assertTrue(DocumentFilterSummary.showsPeriod(periodInPanel = true, applied = base, draft = base))
        val stepped = base.copy(anchor = LocalDate(2026, 9, 21))
        assertTrue(DocumentFilterSummary.showsPeriod(periodInPanel = false, applied = base, draft = stepped))
        val ranged = base.copy(granularity = ReportsGranularity.CUSTOM, customFrom = LocalDate(2026, 10, 1), customTo = LocalDate(2026, 10, 5))
        assertTrue(DocumentFilterSummary.showsPeriod(periodInPanel = false, applied = base, draft = ranged))
    }

    @Test
    fun aTypeTheDraftChangedStaysShownInThePanel() {
        assertFalse(DocumentFilterSummary.showsType(typeInPanel = false, applied = base, draft = base))
        assertTrue(DocumentFilterSummary.showsType(typeInPanel = true, applied = base, draft = base))
        assertTrue(DocumentFilterSummary.showsType(typeInPanel = false, applied = base, draft = base.copy(fileType = "INVOICE")))
    }
}
