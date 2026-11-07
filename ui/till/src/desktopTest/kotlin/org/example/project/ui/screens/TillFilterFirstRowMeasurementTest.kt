package org.example.project.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.FilterBar
import org.example.project.core.presentation.state.DateRange
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.features.financialdocuments.presentation.components.CashBookSummaryCards
import org.example.project.features.financialdocuments.presentation.state.KassenbuchState
import org.example.project.features.reports.presentation.components.dailyreport.DailyReportTab
import org.example.project.features.reports.presentation.state.DashboardViewMode
import org.example.project.features.reports.presentation.state.ReportsUiState
import org.example.project.ui.screens.notifications.NotificationFilter
import org.example.project.ui.screens.notifications.NotificationFilterRow
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Measurement for `docs/testing/till-filter-audit.md` — the y of the first data row of each till
 * list/filter screen at the sizes the till really runs at. Headless, so it needs no display or
 * server: the stateless composables each screen is built from are rendered with sample state and
 * the top of a tagged first row is read back. The result is printed (`FIRSTROW|...`) (read it from the `-i` output).
 *
 * Chrome that wraps these composables on a phone (top app bar 64dp, scrollable tab row 48dp) is added
 * by the audit document, not here.
 */
@OptIn(ExperimentalTestApi::class)
class TillFilterFirstRowMeasurementTest {
    @BeforeTest
    fun translations() = TranslationRegistry.initialize(defaultTranslations())

    private data class Viewport(
        val name: String,
        val w: Int,
        val h: Int,
    )

    private val viewports = listOf(Viewport("360x800", 360, 800), Viewport("390x844", 390, 844), Viewport("1000x600", 1000, 600))

    private fun windowSize(v: Viewport) = WindowSizeClass.compute(v.w.toFloat(), v.h.toFloat())

    private fun firstRowTop(
        v: Viewport,
        find: ComposeUiTest.() -> SemanticsNodeInteraction = { onNodeWithTag("first-row") },
        content: @Composable () -> Unit,
    ): Dp {
        var top = 0.dp
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalWindowSizeClass provides windowSize(v)) {
                    Box(Modifier.size(v.w.dp, v.h.dp)) { content() }
                }
            }
            top = find().getUnclippedBoundsInRoot().top
        }
        return top
    }

    private fun record(
        screen: String,
        v: Viewport,
        top: Dp,
    ) {
        val line = "FIRSTROW|$screen|${v.name}|${top.value.toInt()}dp|${(top.value * 100 / v.h).toInt()}%"
        println(line)
    }

    private val bill =
        BillDto.BillSummaryDto(
            billId = 1,
            billNumber = "1001",
            billAmount = "12.50",
            paidAmount = "12.50",
            tips = "0.00",
            endTime = "2026-10-21T12:00:00",
            employeeNumber = "E1",
            tableNumber = "5",
        )

    private fun dailyReport(
        manager: Boolean,
        v: Viewport,
    ): Dp =
        firstRowTop(v, find = { onNodeWithText("#1001") }) {
            DailyReportTab(
                uiState = ReportsUiState(isLoading = false, canReportShopWide = manager, onlyBills = listOf(bill)),
                selectedViewMode = DashboardViewMode.BILLS,
                availableViewModes = if (manager) DashboardViewMode.entries else listOf(DashboardViewMode.BILLS),
                hasMore = false,
                windowSize = windowSize(v),
                language = Language.GERMAN,
                onDatePresetSelected = {},
                onCustomRangeClick = {},
                onViewModeChanged = {},
                onEmployeeSelected = {},
                onSortChanged = {},
                onLoadCanceledItems = {},
                onLoadTipEarners = {},
                onLoadPaymentRecords = {},
                onDismissPaymentsBottomSheet = {},
                onLoadMore = {},
                onBillClick = {},
                onToggleTrainingMode = if (manager) ({}) else null,
                availablePaymentTypes = listOf("BAR", "EC-KARTE"),
            )
        }

    @Test
    fun `tagesbericht first bill row`() {
        viewports.forEach { v ->
            record("Tagesbericht Kassierer (DailyReportTab ohne AppBar/Tab)", v, dailyReport(false, v))
            record("Tagesbericht Manager (DailyReportTab ohne AppBar/Tab)", v, dailyReport(true, v))
        }
    }

    @Test
    fun `date range filter bar first row`() {
        viewports.forEach { v ->
            val zbon =
                firstRowTop(v) {
                    Column {
                        FilterBar(DateRange.TODAY, null, {}, {}, Language.GERMAN)
                        Text("row", Modifier.testTag("first-row"))
                    }
                }
            record("Z-Bon-Liste (FilterBar nur Zeitraum)", v, zbon)
            val kassenbuch =
                firstRowTop(v) {
                    Column {
                        FilterBar(DateRange.TODAY, null, {}, {}, Language.GERMAN, searchTerm = "", onSearchTermChanged = {}, searchPlaceholder = "Suchen")
                        Text("row", Modifier.testTag("first-row"))
                    }
                }
            record("Kassenbuch (nur FilterBar; Kassenauswahl + KPI-Karten kommen davor)", v, kassenbuch)
        }
    }

    @Test
    fun `notification filter row first row`() {
        viewports.forEach { v ->
            val top =
                firstRowTop(v) {
                    Column {
                        NotificationFilterRow(NotificationFilter.ALL, unreadCount = 3, onFilterSelected = {})
                        Text("row", Modifier.testTag("first-row"))
                    }
                }
            record("Benachrichtigungen (Chip-Zeile)", v, top)
        }
    }

    @Test
    fun `kassenbuch compact with summary cards first row`() {
        viewports.forEach { v ->
            val top =
                firstRowTop(v) {
                    Column {
                        CashBookSummaryCards(KassenbuchState(), Language.GERMAN, isCompact = windowSize(v).isCompact())
                        FilterBar(DateRange.TODAY, null, {}, {}, Language.GERMAN, searchTerm = "", onSearchTermChanged = {}, searchPlaceholder = "Suchen")
                        Text("row", Modifier.testTag("first-row"))
                    }
                }
            record("Kassenbuch (KPI-Karten + FilterBar, ohne Kassenauswahl/AppBar/Tabs)", v, top)
        }
    }
}
