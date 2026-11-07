package org.example.project.admin.features.dailyreport.presentation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import org.example.project.admin.core.auth.holds
import org.example.project.admin.core.presentation.components.AdminPageTitle
import org.example.project.admin.core.presentation.components.AdminReceiptPane
import org.example.project.admin.core.presentation.navigation.FilterPanelKeys
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.presentation.components.picker.CustomDateRangePickerDialog
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalDisplayCurrency
import org.example.project.features.reports.presentation.components.dailyreport.DailyReportFilterPresentation
import org.example.project.features.reports.presentation.components.dailyreport.DailyReportTab
import org.example.project.features.reports.presentation.state.DashboardViewMode
import org.example.project.features.reports.presentation.state.ReportsViewMode
import org.example.project.ui.components.PosBillDetailPanel
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosLoadingOverlay
import org.example.project.ui.layout.PaneBreakpoints
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.example.project.features.reports.presentation.viewmodel.ReportsViewModel as DailyReportViewModel

/**
 * The till's daily report, in the back office.
 *
 * Deliberately the same screen rather than a second implementation of it: the tab, its filter bar,
 * the KPI cards and the transaction list all moved down to `shared-ui`, so what runs here is the
 * code the till runs. What this file adds is the wiring — which view model, which currency, which
 * window size — and the host for the receipt panel a transaction row opens, which is the same
 * `PosBillDetailPanel` the Z-Bon hub's session detail shows.
 *
 * The Z-Bon tab is not here. In the till the report and the Z-Bon history share a tab bar; the back
 * office already has a Z-Bons screen of its own under Kasse & Abschluss, and a second copy behind a
 * tab would be two routes to one thing.
 */
@Composable
fun DailyReportScreen(
    // Explicit store key: the ViewModel store keys off the SIMPLE class name on wasm. The class
    // is shared-ui's ReportsViewModel; adminApp's own Analysen VM was renamed AnalyticsViewModel
    // (T9-3), because Koin's definition index uses the simple name too, and there a store key does
    // not help. Aliasing the import is only a compile-time rename.
    viewModel: DailyReportViewModel = koinViewModel(key = "dailyReportViewModel"),
    tokenStore: TokenStore = koinInject(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val dashboardViewMode by viewModel.dashboardViewMode.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val language = LocalAppLanguage.current
    val windowSize = currentWindowAdaptiveInfo().windowSizeClass
    val seesEveryone = tokenStore.holds(Capability.REPORT_ALL_READ)

    LaunchedEffect(seesEveryone) { viewModel.determineViewMode(seesEveryone) }

    // The view model is retained app-wide (see the store key on the parameter), so a receipt left open
    // before navigating away would still cover the report on the way back in — possibly one belonging
    // to the previous session. Entering the screen always starts with the report itself.
    LaunchedEffect(Unit) { viewModel.dismissBillPanel() }

    PosErrorWatcher(
        error = uiState.error,
        title = str(AdminStringKeys.DailyReportTitle),
        onClear = viewModel::clearError,
        retryAction = viewModel::refresh,
    )

    val availableViewModes =
        if (viewMode == ReportsViewMode.MANAGER_PLUS) {
            DashboardViewMode.entries
        } else {
            listOf(DashboardViewMode.BILLS)
        }

    val screenPadding = LocalPosLayout.current.screenPadding
    // Vertical padding only: the filter bar, KPIs and rows each take the screen padding, so the
    // edge is single (an outer horizontal padding would double it under PosFilterBar's own).
    Column(Modifier.fillMaxSize().padding(vertical = PosSpacing.xl)) {
        AdminPageTitle(str(AdminStringKeys.DailyReportTitle), Modifier.padding(horizontal = screenPadding))
        // The shared money formatters read this rather than composeApp's setting manager. The
        // period's own currency is the honest source: it is what the figures beside it are in.
        CompositionLocalProvider(LocalDisplayCurrency provides uiState.currency) {
            val selectedBill = uiState.selectedBill
            AdminReceiptPane(
                panelOpen = uiState.showBillPanel && selectedBill != null,
                list = {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        // Content width, not the window class: the sidebar takes a third of a tablet.
                        val compact = maxWidth < PaneBreakpoints.PHONE_MAX
                        DailyReportTab(
                            uiState = uiState,
                            selectedViewMode = dashboardViewMode,
                            availableViewModes = availableViewModes,
                            hasMore =
                                when (dashboardViewMode) {
                                    DashboardViewMode.BILLS -> uiState.hasMoreBills
                                    DashboardViewMode.TABLES -> uiState.hasMoreTableCards
                                    DashboardViewMode.EMPLOYEES -> uiState.hasMoreEmployeeGroups
                                },
                            windowSize = windowSize,
                            compact = compact,
                            headerScrollsWithList = compact || PaneBreakpoints.isShortWindow(maxHeight),
                            // The Zenbon screen padding is the single horizontal edge (the filter bar pads itself with it).
                            contentInset = screenPadding,
                            presentation = DailyReportFilterPresentation.Panel,
                            filterPanelKey = FilterPanelKeys.DAILY_REPORT,
                            language = language,
                            onDatePresetSelected = viewModel::onDatePresetSelected,
                            onCustomRangeClick = viewModel::showCustomDateRangeDialog,
                            onViewModeChanged = viewModel::onDashboardViewModeChanged,
                            onEmployeeSelected = viewModel::onEmployeeSelected,
                            onSortChanged = viewModel::onSortChanged,
                            onLoadCanceledItems = viewModel::loadCanceledItems,
                            onLoadTipEarners = viewModel::loadTipEarners,
                            onLoadPaymentRecords = viewModel::loadPaymentRecords,
                            onDismissPaymentsBottomSheet = viewModel::dismissPaymentsBottomSheet,
                            onLoadMore = viewModel::loadMore,
                            // The list row carries only a summary, so the view model fetches the full bill
                            // by id and raises `showBillPanel`; the receipt is the one the Z-Bon hub
                            // renders, not a second implementation of it.
                            onBillClick = viewModel::onBillSummaryClick,
                            onToggleTrainingMode = if (seesEveryone) viewModel::toggleTrainingMode else null,
                            availablePaymentTypes = uiState.availablePaymentTypes,
                            selectedPaymentTypes = uiState.selectedPaymentTypes,
                            onPaymentTypesChanged = viewModel::onPaymentTypesChanged,
                            expandedTableSessions = uiState.expandedTableSessions,
                            loadingTableSessions = uiState.loadingTableSessions,
                            onToggleTableExpand = { tableId ->
                                if (uiState.expandedTableSessions.containsKey(tableId)) {
                                    viewModel.collapseTable(tableId)
                                } else {
                                    viewModel.loadTableSessions(tableId)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                panel = {
                    if (selectedBill != null) {
                        // Bounded height, no outer scroll: the panel keeps its own scrolling body
                        // and pins its Close button, and an unbounded parent would measure that
                        // inner scroller with infinite constraints.
                        PosBillDetailPanel(
                            bill = selectedBill,
                            tableNumber = selectedBill.tableNumber,
                            currency = uiState.currency,
                            onClose = viewModel::dismissBillPanel,
                            closeInHeader = true,
                            // Related-bill links open the linked bill in this same panel.
                            onOpenLinkedBill = viewModel::openLinkedBill,
                            backTo = uiState.billTrail.backTo,
                            onBack = viewModel::backToPreviousBill,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                },
            )
        }
    }

    // The fetch behind a row tap costs a round trip. Failures land in `uiState.error` and are
    // surfaced by the watcher above; this covers the wait, so the tap is never a silent no-op.
    if (uiState.isLoadingBill) {
        PosLoadingOverlay(title = str(AdminStringKeys.DailyReportBillLoading))
    }

    // "Benutzerdefiniert" in the filter bar only raises this flag; without a host here the choice
    // closed the dropdown and did nothing (FO-40). Same dialog and same range format as the till.
    if (uiState.showCustomDateRangeDialog) {
        CustomDateRangePickerDialog(
            language = language,
            onDismiss = viewModel::dismissCustomDateRangeDialog,
            onDateRangeSelected = { startDate, endDate ->
                viewModel.dismissCustomDateRangeDialog()
                viewModel.onCustomDateRangeSelected(
                    start = "${startDate}T00:00:00",
                    end = "${endDate}T23:59:59",
                )
            },
        )
    }
}
