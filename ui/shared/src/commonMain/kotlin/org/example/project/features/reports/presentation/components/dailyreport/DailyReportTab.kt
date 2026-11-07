package org.example.project.features.reports.presentation.components.dailyreport

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.StatisticsDashboardKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.isCompact
import org.example.project.features.reports.presentation.components.bottomsheet.BillsBottomSheet
import org.example.project.features.reports.presentation.components.bottomsheet.CanceledBottomSheet
import org.example.project.features.reports.presentation.components.bottomsheet.PaymentsBottomSheet
import org.example.project.features.reports.presentation.components.bottomsheet.RevenueBottomSheet
import org.example.project.features.reports.presentation.components.bottomsheet.TipsBottomSheet
import org.example.project.features.reports.presentation.state.DashboardViewMode
import org.example.project.features.reports.presentation.state.DateFilterPreset
import org.example.project.features.reports.presentation.state.ReportsUiState
import org.example.project.ui.components.PosKpiSummary
import org.example.project.ui.components.filter.filterResultCount
import org.example.project.ui.components.filter.rememberPosFilterPanelState
import org.example.project.ui.components.kpiSummaryText
import org.example.project.ui.screens.reports.components.KpiCardGrid
import org.example.project.ui.theme.LocalPosLayout

@Composable
fun DailyReportTab(
    uiState: ReportsUiState,
    selectedViewMode: DashboardViewMode,
    availableViewModes: List<DashboardViewMode>,
    hasMore: Boolean,
    windowSize: WindowSizeClass,
    language: Language,
    onDatePresetSelected: (DateFilterPreset) -> Unit,
    onCustomRangeClick: () -> Unit,
    onViewModeChanged: (DashboardViewMode) -> Unit,
    onEmployeeSelected: (String?) -> Unit,
    onSortChanged: (StatisticsDto.StatisticsSortOption) -> Unit,
    onLoadCanceledItems: () -> Unit,
    onLoadTipEarners: () -> Unit,
    onLoadPaymentRecords: () -> Unit,
    onDismissPaymentsBottomSheet: () -> Unit,
    onLoadMore: () -> Unit,
    onBillClick: (BillDto.BillSummaryDto) -> Unit,
    onToggleTrainingMode: (() -> Unit)? = null,
    availablePaymentTypes: List<String> = emptyList(),
    selectedPaymentTypes: Set<String> = emptySet(),
    onPaymentTypesChanged: (Set<String>) -> Unit = {},
    expandedTableSessions: Map<Long, List<StatisticsDto.TableSessionSummaryDto>> = emptyMap(),
    loadingTableSessions: Set<Long> = emptySet(),
    onToggleTableExpand: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    compact: Boolean = windowSize.isCompact(),
    headerScrollsWithList: Boolean = false,
    // Horizontal edge of the KPI cards, payment row, filter bar and bill rows; null keeps each one's
    // own default. A host that already pads its content (adminApp) passes 0 so there is a single edge.
    contentInset: Dp? = null,
    // Scroller is the till's filter row; Panel is the back office's Zenbon filter bar + panel.
    presentation: DailyReportFilterPresentation = DailyReportFilterPresentation.Scroller,
    // Key under which the Panel presentation's state lives in LocalPosFilterPanelStore (null: screen-local).
    filterPanelKey: String? = null,
) {
    var activeBottomSheet by remember { mutableStateOf<KpiCardType?>(null) }
    val isCompact = compact
    // The Panel presentation's filter panel lives here, above the [headerScrollsWithList] branch that
    // moves the bar between a fixed header and the list's first item: a resize across it keeps an
    // open panel and its draft (and the panel never depends on the header item being composed).
    val panelApplied =
        DailyReportPanelFilter(uiState.selectedEmployeeId, uiState.trainingMode, uiState.sortOption, selectedPaymentTypes)
    val filterPanel = rememberPosFilterPanelState(panelApplied, filterPanelKey)

    // Filter bar, KPI cards and payment row. Fixed above the list by default; with
    // [headerScrollsWithList] they are the list's first item so a short window keeps its rows.
    val topSection: @Composable () -> Unit = {
        ReportsFilterBar(
            selectedPreset = uiState.selectedDatePreset,
            onPresetSelected = onDatePresetSelected,
            onCustomRangeClick = onCustomRangeClick,
            selectedViewMode = selectedViewMode,
            onViewModeChanged = onViewModeChanged,
            canReportShopWide = uiState.canReportShopWide,
            availableEmployees = uiState.availableEmployees,
            selectedEmployeeId = uiState.selectedEmployeeId,
            onEmployeeSelected = onEmployeeSelected,
            selectedSort = uiState.sortOption,
            onSortChanged = onSortChanged,
            availableViewModes = availableViewModes,
            isCompact = isCompact,
            trainingMode = uiState.trainingMode,
            onToggleTrainingMode = onToggleTrainingMode,
            availablePaymentTypes = availablePaymentTypes,
            selectedPaymentTypes = selectedPaymentTypes,
            onPaymentTypesChanged = onPaymentTypesChanged,
            horizontalInset = contentInset,
            presentation = presentation,
            filterPanelState = filterPanel,
            resultText =
                if (presentation == DailyReportFilterPresentation.Panel) {
                    dailyReportResultText(uiState, selectedViewMode, hasMore)
                } else {
                    ""
                },
        )

        val kpiCards: @Composable (Modifier) -> Unit = { gridModifier ->
            KpiCardGrid(
                totalRevenue = uiState.totalRevenue,
                totalTips = uiState.totalTips,
                totalBillCount = uiState.totalBillCount,
                canceledRevenue = uiState.canceledRevenue,
                openAmount = uiState.openItemAmount,
                windowSize = windowSize,
                // The till keeps its sideways strip; the back office never uses a strip (adminApp
                // phone rule 2): the wrapping grid, expanded from the summary line on a phone.
                compact = compact && presentation == DailyReportFilterPresentation.Scroller,
                onCardClick = { type -> activeBottomSheet = type },
                modifier = gridModifier,
            )
        }
        val kpiInset = Modifier.padding(horizontal = contentInset ?: 16.dp)
        if (presentation == DailyReportFilterPresentation.Panel && LocalPosLayout.current.isCompact) {
            // Phone: one summary line (revenue · tips) that expands to the same cards.
            PosKpiSummary(
                summary = dailyReportKpiSummary(uiState),
                modifier = kpiInset,
                content = { kpiCards(Modifier.fillMaxWidth()) },
            )
        } else {
            kpiCards(Modifier.fillMaxWidth().then(kpiInset))
        }

        Spacer(modifier = Modifier.height(8.dp))

        PaymentDotsRow(
            payments = uiState.revenueByPaymentMethod,
            onClick = onLoadPaymentRecords,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = contentInset ?: 16.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))
    }

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        if (!headerScrollsWithList) topSection()

        TransactionList(
            header = if (headerScrollsWithList) topSection else null,
            viewMode = selectedViewMode,
            bills = uiState.onlyBills,
            tableCards = uiState.tableCards,
            expandedTableSessions = expandedTableSessions,
            loadingTableSessions = loadingTableSessions,
            onToggleTableExpand = onToggleTableExpand,
            employeeGroups = uiState.employeeGroups,
            isCompact = isCompact,
            isLoading = uiState.isLoading || uiState.isLoadingDetail,
            isLoadingMore = uiState.isLoadingMore,
            hasMore = hasMore,
            onLoadMore = onLoadMore,
            onBillClick = onBillClick,
            scrollToTopRequest = uiState.scrollToTopRequest,
            contentInset = contentInset,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
        )
    }

    if (presentation == DailyReportFilterPresentation.Panel) {
        ReportsFilterPanel(
            panelState = filterPanel,
            applied = panelApplied,
            gates = DailyReportPanelGates.of(uiState.canReportShopWide, selectedViewMode, trainingShown = onToggleTrainingMode != null),
            actions = DailyReportPanelActions(onEmployeeSelected, onToggleTrainingMode, onSortChanged, onPaymentTypesChanged),
            availableEmployees = uiState.availableEmployees,
            availablePaymentTypes = availablePaymentTypes,
        )
    }

    // Bottom sheet management
    when (activeBottomSheet) {
        KpiCardType.REVENUE ->
            RevenueBottomSheet(
                totalRevenue = uiState.totalRevenue,
                billedRevenue = uiState.billedRevenue,
                refundRevenue = uiState.refundRevenue,
                totalTips = uiState.totalTips,
                totalDiscounts = uiState.totalDiscounts,
                payments = uiState.revenueByPaymentMethod,
                receivableCleared = uiState.receivableCleared,
                onDismiss = { activeBottomSheet = null },
            )

        KpiCardType.TIPS ->
            TipsBottomSheet(
                totalTips = uiState.totalTips,
                averagePerBill = uiState.averageTipPerBill,
                employeeGroups = uiState.tipEarners,
                currency = uiState.currency,
                canReportShopWide = uiState.canReportShopWide,
                onLoadTipEarners = onLoadTipEarners,
                onDismiss = { activeBottomSheet = null },
            )

        KpiCardType.BILLS ->
            BillsBottomSheet(
                totalBillCount = uiState.totalBillCount,
                totalRevenue = uiState.totalRevenue,
                openItemAmount = uiState.openItemAmount,
                employeeGroups = uiState.employeeGroups,
                canReportShopWide = uiState.canReportShopWide,
                onDismiss = { activeBottomSheet = null },
            )

        KpiCardType.CANCELED ->
            CanceledBottomSheet(
                canceledRevenue = uiState.canceledRevenue,
                canceledItems = uiState.canceledItems,
                isLoading = uiState.isLoadingCanceledItems,
                onLoadCanceledItems = onLoadCanceledItems,
                onDismiss = { activeBottomSheet = null },
            )

        null -> Unit
    }

    // Payments bottom sheet
    if (uiState.showPaymentsBottomSheet) {
        PaymentsBottomSheet(
            payments = uiState.paymentRecords,
            isLoading = uiState.isLoadingPayments,
            onDismiss = onDismissPaymentsBottomSheet,
        )
    }
}

/** The Panel bar's result line: what the current view lists, "50+" while further pages exist. */
@Composable
private fun dailyReportResultText(
    uiState: ReportsUiState,
    viewMode: DashboardViewMode,
    hasMore: Boolean,
): String =
    when (viewMode) {
        DashboardViewMode.BILLS ->
            str(StatisticsDashboardKeys.resultBills, filterResultCount(uiState.onlyBills.size, hasMore))
        DashboardViewMode.TABLES ->
            str(StatisticsDashboardKeys.resultTables, filterResultCount(uiState.tableCards.size, hasMore))
        DashboardViewMode.EMPLOYEES ->
            str(StatisticsDashboardKeys.resultEmployees, filterResultCount(uiState.employeeGroups.size, hasMore))
    }

/**
 * The collapsed phone KPI line: revenue · tips. Until a report has loaded the state still holds its
 * "0,00 €" defaults, which nobody computed, so the figures show the placeholder instead (the state's
 * own [ReportsUiState.averageTipPerBill] is empty exactly until then).
 */
@Composable
private fun dailyReportKpiSummary(uiState: ReportsUiState): String =
    kpiSummaryText(
        dailyReportKpiSummaryItems(
            averageTipPerBill = uiState.averageTipPerBill,
            revenue = uiState.totalRevenue,
            tips = uiState.totalTips,
            revenueLabel = str(StatisticsDashboardKeys.totalRevenue),
            tipsLabel = str(StatisticsDashboardKeys.tips),
        ),
    )
