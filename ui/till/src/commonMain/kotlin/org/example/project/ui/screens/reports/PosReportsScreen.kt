package org.example.project.ui.screens.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.auth.AccessPolicy
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.components.picker.CustomDateRangePickerDialog
import org.example.project.core.presentation.navigation.BackHandler
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.AccessibilityKeys
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.presentation.strings.keys.ReportsScreenKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalDisplayCurrency
import org.example.project.core.utils.LocalDrawerController
import org.example.project.core.utils.LocalNotificationViewModel
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.features.notifications.presentation.viewmodel.NotificationUiEvent
import org.example.project.features.reports.presentation.components.dailyreport.DailyReportTab
import org.example.project.features.reports.presentation.components.zBon.TipPayoutDialog
import org.example.project.features.reports.presentation.components.zBon.TipReversalDialog
import org.example.project.features.reports.presentation.state.DashboardViewMode
import org.example.project.features.reports.presentation.state.ReportsViewMode
import org.example.project.features.reports.presentation.state.ZBonClosingDraft
import org.example.project.features.reports.presentation.viewmodel.ReportsViewModel
import org.example.project.features.reports.presentation.viewmodel.ZBonViewModel
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.auth.holds
import org.example.project.ui.components.PosBillReceiptOverlay
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosMarqueeText
import org.example.project.ui.components.PosNavIconButton
import org.example.project.ui.components.PosSuccessWatcher
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.screens.reports.components.BillSearchBottomSheet
import org.example.project.ui.screens.reports.components.CreateZBonConfirmDialog
import org.example.project.ui.screens.reports.components.CreatedZBonDialog
import org.example.project.ui.screens.reports.components.ZBonTab
import org.koin.compose.viewmodel.koinViewModel

/**
 * Redesigned Reports screen with adaptive layout.
 *
 * Features:
 * - Phone: tabs (Overview / Z-Bon), report cards with revenue/orders stats, FABs
 * - Tablet/Desktop: wider layout with tab row + actions in top bar
 * - Overview tab: summary cards (revenue, orders, avg order), daily report
 * - Z-Bon tab: Z-Bon history list with create button
 * - Uses existing ReportsViewModel + ZBonViewModel
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosReportsScreen(
    modifier: Modifier = Modifier,
    language: Language,
    viewModel: ReportsViewModel = koinViewModel(),
    zBonViewModel: ZBonViewModel = koinViewModel(),
) {
    val windowSize = LocalWindowSizeClass.current
    val drawerController = LocalDrawerController.current
    BackHandler(enabled = true, onBack = {
        if (drawerController?.isDrawerAvailable == true) {
            drawerController.toggleDrawer()
        }
    })

    val overlay = LocalGlobalOverlayViewModel.current

    val uiState by viewModel.uiState.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val dashboardViewMode by viewModel.dashboardViewMode.collectAsState()
    val zBonState by zBonViewModel.zBonState.collectAsState()

    val isCompact = windowSize.isCompact()

    // Nine controls on this screen used to hang off `role.isManagerOrHigher()`. Every one of them
    // shows or hides SHOP-WIDE reporting — the date-range picker, the employee filter, the
    // training-mode toggle, the per-employee tabs — which is exactly what REPORT_ALL_READ names and
    // what the server scopes on. A role could not express it: CASHIER and MANAGER both hold
    // REPORT_READ, and an AUDITOR may carry either role.
    val seesEveryone = holds(Capability.REPORT_ALL_READ)

    // Closing the day is a separate, RESERVED authority. "Z-Bon erstellen" used to hang off
    // seesEveryone, so an auditor or accountant (REPORT_ALL_READ, no ZBON_CLOSE) was offered a
    // button POST /api/z-bon answers with 403 (T5-5). ZBonRules gates that route on ZBON_CLOSE.
    val closesDay = mayCreateZBon(LocalAccessPolicy.current)

    // The Z-Bon history reads /api/z-bon/recent, /{id}, /history and /{id}/pdf — all ZBON_MANAGE in
    // ZBonRules. It used to show on seesEveryone, so a group holding REPORT_ALL_READ without
    // ZBON_MANAGE opened a tab every call of which answered 403. A stale index falls back to the
    // daily report rather than rendering the tab the policy withholds.
    val readsZBonHistory = mayReadZBonHistory(LocalAccessPolicy.current)

    // Paying an employee's tips out of a closing writes a cash withdrawal into the fiscal book, which
    // ZBonRules gates on CASHBOOK_WRITE (the capability of every other Kassenbuch withdrawal). Without
    // it the panel still lists the open amounts, but offers no action.
    val currencyCode = LocalDisplayCurrency.current
    val onPayOutTip: ((Long, ZBonDto.EmployeeTipPayoutStatusDto) -> Unit)? =
        if (mayPayOutTips(LocalAccessPolicy.current)) {
            { zBonId, status -> zBonViewModel.openTipPayout(zBonId, status) }
        } else {
            null
        }
    // Stornoing a payout books its Kassenbuch correction — the same capability as the payout.
    val onReverseTipPayout: ((Long, ZBonDto.EmployeeTipPayoutStatusDto, ZBonDto.TipPayoutRecordDto) -> Unit)? =
        if (mayPayOutTips(LocalAccessPolicy.current)) {
            { zBonId, status, record -> zBonViewModel.openTipReversal(zBonId, status, record) }
        } else {
            null
        }
    val selectedTab = if (readsZBonHistory) uiState.selectedTabIndex else 0

    LaunchedEffect(seesEveryone) {
        viewModel.determineViewMode(seesEveryone)
    }

    // A bill written on another device (or by the API) closes or part-pays a table, and the server
    // pushes that table's new status. The list fixed its range on entry, so without this such a bill
    // only showed after leaving and re-entering Berichte (emu17 NOTE-c). The VM coalesces the pushes
    // and reloads only when the bill count actually moved.
    val notificationViewModel = LocalNotificationViewModel.current
    LaunchedEffect(Unit) {
        notificationViewModel.notificationEvents.collect { event ->
            if (event is NotificationUiEvent.TableStatusChanged) viewModel.onBillsMayHaveChangedElsewhere()
        }
    }

    PosErrorWatcher(
        error = uiState.error,
        title = getString(ReportsKeys.screenTitle, language),
        onClear = { viewModel.clearError() },
        displayMode = DisplayMode.DIALOG,
        retryAction = { viewModel.loadStatistics() },
    )

    PosErrorWatcher(
        error = zBonState.error,
        title = getString(ReportsKeys.screenTitle, language),
        onClear = { zBonViewModel.clearError() },
        displayMode = DisplayMode.DIALOG,
    )

    // Z-Bon success handling
    PosSuccessWatcher(
        message = zBonState.successMessage,
        title = getString(ReportsKeys.screenTitle, language),
        onClear = { zBonViewModel.clearSuccessMessage() },
    )

    val availableViewModes =
        if (viewMode == ReportsViewMode.MANAGER_PLUS) {
            DashboardViewMode.entries
        } else {
            listOf(DashboardViewMode.BILLS)
        }

    Box(modifier = modifier.fillMaxSize()) {
        if (isCompact) {
            // Compact layout: ScrollableTabRow + FABs
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            if (drawerController?.isDrawerAvailable == true) {
                                PosNavIconButton(
                                    icon = Icons.Default.Menu,
                                    contentDescription = str(AccessibilityKeys.menu),
                                    onClick = { drawerController.toggleDrawer() },
                                )
                            }
                        },
                        title = {
                            PosMarqueeText(
                                text =
                                    if (seesEveryone) {
                                        str(ReportsKeys.viewTitleAdmin)
                                    } else {
                                        str(ReportsKeys.viewTitleCashier)
                                    },
                            )
                        },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    )
                },
                floatingActionButton = {
                    if (selectedTab == 0) {
                        Column(
                            horizontalAlignment = Alignment.End,
                        ) {
                            SmallFloatingActionButton(
                                onClick = { viewModel.showBillSearchSheet() },
                            ) {
                                Icon(Icons.Default.Search, contentDescription = str(ReportsScreenKeys.searchBill))
                            }
                            if (closesDay) {
                                Spacer(modifier = Modifier.padding(vertical = 4.dp))
                                ExtendedFloatingActionButton(
                                    text = { Text(str(ReportsScreenKeys.createZBon)) },
                                    icon = {
                                        Icon(Icons.Default.Receipt, contentDescription = null)
                                    },
                                    onClick = { viewModel.showZBonConfirmDialog() },
                                )
                            }
                        }
                    }
                },
            ) { paddingValues ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                ) {
                    if (seesEveryone) {
                        PrimaryScrollableTabRow(selectedTabIndex = selectedTab) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { viewModel.selectTab(0) },
                                text = { Text(str(ReportsScreenKeys.dailyReport)) },
                            )
                            if (readsZBonHistory) {
                                Tab(
                                    selected = selectedTab == 1,
                                    onClick = { viewModel.selectTab(1) },
                                    text = { Text(str(ReportsScreenKeys.zBonHistory)) },
                                )
                            }
                        }
                    }

                    when (selectedTab) {
                        0 ->
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
                                language = language,
                                onDatePresetSelected = { viewModel.onDatePresetSelected(it) },
                                onCustomRangeClick = { viewModel.showCustomDateRangeDialog() },
                                onViewModeChanged = { viewModel.onDashboardViewModeChanged(it) },
                                onEmployeeSelected = { viewModel.onEmployeeSelected(it) },
                                onSortChanged = { viewModel.onSortChanged(it) },
                                onLoadCanceledItems = { viewModel.loadCanceledItems() },
                                onLoadTipEarners = { viewModel.loadTipEarners() },
                                onLoadPaymentRecords = { viewModel.loadPaymentRecords() },
                                onDismissPaymentsBottomSheet = { viewModel.dismissPaymentsBottomSheet() },
                                onLoadMore = { viewModel.loadMore() },
                                onBillClick = { summary -> viewModel.onBillSummaryClick(summary) },
                                onToggleTrainingMode =
                                    if (seesEveryone) {
                                        { viewModel.toggleTrainingMode() }
                                    } else {
                                        null
                                    },
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
                                modifier = Modifier.fillMaxSize(),
                            )

                        1 ->
                            ZBonTab(
                                viewModel = zBonViewModel,
                                onLoadStatistics = { startTime, endTime ->
                                    viewModel.showZBonPeriod(startTime, endTime)
                                },
                                language = language,
                                modifier = Modifier.fillMaxSize(),
                                onPayOutTip = onPayOutTip,
                                onReverseTipPayout = onReverseTipPayout,
                            )
                    }
                }
            }
        } else {
            // Desktop/Tablet layout: TabRow with actions in TopAppBar
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            if (drawerController?.isDrawerAvailable == true) {
                                PosNavIconButton(
                                    icon = Icons.Default.Menu,
                                    contentDescription = str(AccessibilityKeys.menu),
                                    onClick = { drawerController.toggleDrawer() },
                                )
                            }
                        },
                        title = {
                            Column {
                                PosMarqueeText(
                                    text =
                                        if (seesEveryone) {
                                            str(ReportsKeys.viewTitleAdmin)
                                        } else {
                                            str(ReportsKeys.viewTitleCashier)
                                        },
                                )
                                Text(
                                    text =
                                        if (seesEveryone) {
                                            str(ReportsKeys.viewDescriptionAdmin)
                                        } else {
                                            str(ReportsKeys.viewDescriptionCashier)
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    )
                },
            ) { paddingValues ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                ) {
                    if (seesEveryone) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PrimaryScrollableTabRow(
                                selectedTabIndex = selectedTab,
                                modifier = Modifier.weight(1f, fill = false),
                            ) {
                                Tab(
                                    selected = selectedTab == 0,
                                    onClick = { viewModel.selectTab(0) },
                                    text = { Text(str(ReportsScreenKeys.dailyReport)) },
                                )
                                if (readsZBonHistory) {
                                    Tab(
                                        selected = selectedTab == 1,
                                        onClick = { viewModel.selectTab(1) },
                                        text = { Text(str(ReportsScreenKeys.zBonHistory)) },
                                    )
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                PosTextButton(
                                    text = str(ReportsScreenKeys.searchBill),
                                    icon = Icons.Default.Search,
                                    onClick = { viewModel.showBillSearchSheet() },
                                )
                                if (closesDay) {
                                    PosTextButton(
                                        text = str(ReportsScreenKeys.createZBon),
                                        icon = Icons.Default.Receipt,
                                        onClick = { viewModel.showZBonConfirmDialog() },
                                    )
                                }
                            }
                        }
                    }

                    when (selectedTab) {
                        0 ->
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
                                language = language,
                                onDatePresetSelected = { viewModel.onDatePresetSelected(it) },
                                onCustomRangeClick = { viewModel.showCustomDateRangeDialog() },
                                onViewModeChanged = { viewModel.onDashboardViewModeChanged(it) },
                                onEmployeeSelected = { viewModel.onEmployeeSelected(it) },
                                onSortChanged = { viewModel.onSortChanged(it) },
                                onLoadCanceledItems = { viewModel.loadCanceledItems() },
                                onLoadTipEarners = { viewModel.loadTipEarners() },
                                onLoadPaymentRecords = { viewModel.loadPaymentRecords() },
                                onDismissPaymentsBottomSheet = { viewModel.dismissPaymentsBottomSheet() },
                                onLoadMore = { viewModel.loadMore() },
                                onBillClick = { summary -> viewModel.onBillSummaryClick(summary) },
                                onToggleTrainingMode =
                                    if (seesEveryone) {
                                        { viewModel.toggleTrainingMode() }
                                    } else {
                                        null
                                    },
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
                                modifier = Modifier.fillMaxSize(),
                            )

                        1 ->
                            ZBonTab(
                                viewModel = zBonViewModel,
                                onLoadStatistics = { startTime, endTime ->
                                    viewModel.showZBonPeriod(startTime, endTime)
                                },
                                language = language,
                                modifier = Modifier.fillMaxSize(),
                                onPayOutTip = onPayOutTip,
                                onReverseTipPayout = onReverseTipPayout,
                            )
                    }
                }
            }
        }

        // Bill receipt panel (slides from right)
        PosBillReceiptOverlay(
            isVisible = uiState.showBillPanel,
            bill = uiState.selectedBill,
            tableNumber = uiState.selectedBill?.tableNumber ?: "",
            language = language,
            onDismiss = { viewModel.dismissBillPanel() },
            compact = isCompact,
            // Related-bill links open the linked bill in this same panel; "back" returns along the trail.
            onOpenLinkedBill = viewModel::openLinkedBill,
            backTo = uiState.billTrail.backTo,
            onBack = viewModel::backToPreviousBill,
            // A Zahlartwechsel shows the new bill with the original on the back trail and reloads the list.
            onRebooked = viewModel::onBillRebooked,
            // A refused or failed rebook re-reads the bill, which may no longer stand.
            onRebookFailed = viewModel::onRebookFailed,
            // A Storno, a refused Storno, an issued invoice or a till settlement reloads the list
            // and cards over "up to now" as well.
            onVoided = viewModel::onBillVoided,
            onBillChanged = viewModel::onBillChanged,
        )
    }

    // Bill search bottom sheet
    if (uiState.showBillSearchSheet) {
        BillSearchBottomSheet(
            language = language,
            onDismiss = { viewModel.dismissBillSearchSheet() },
            onBillFound = { bill -> viewModel.onBillFound(bill) },
        )
    }

    // Load the drawer expectation — and, for an operator who may pay out tips, the open period's
    // employee tips (F58) — when the closing dialog opens, so the count and the payouts can be prefilled.
    val paysOutTipsInClosing = mayPayOutTips(LocalAccessPolicy.current)
    LaunchedEffect(uiState.showZBonConfirmDialog) {
        if (uiState.showZBonConfirmDialog) zBonViewModel.openClosingDialog(includeTipPayouts = paysOutTipsInClosing)
    }

    // Z-Bon confirmation dialog
    if (uiState.showZBonConfirmDialog && closesDay) {
        CreateZBonConfirmDialog(
            language = language,
            draft = zBonState.closingDraft ?: ZBonClosingDraft(),
            currencyCode = currencyCode,
            onCountChange = zBonViewModel::updateClosingCount,
            onTipCheckedChange = zBonViewModel::setClosingTipChecked,
            onTipAmountChange = zBonViewModel::updateClosingTipAmount,
            onTipMethodChange = zBonViewModel::setClosingTipMethod,
            onTopUpSourceSelect = zBonViewModel::selectClosingTopUpSource,
            onTopUpAmountChange = zBonViewModel::updateClosingTopUpAmount,
            onConfirm = {
                viewModel.dismissZBonConfirmDialog()
                // The count and the payouts travel with the closing in ONE request; the server checks
                // everything first and books the payouts inside the closing, the count against the
                // drawer after them. Nothing prints here: the new closing opens on screen instead — in
                // the history tab when the operator may read it, otherwise in the summary dialog below.
                zBonViewModel.confirmClosing(opensHistory = readsZBonHistory) {
                    viewModel.onZBonCreated(opensHistory = readsZBonHistory)
                }
            },
            onDismiss = {
                viewModel.dismissZBonConfirmDialog()
                zBonViewModel.dismissClosingDialog()
            },
        )
    }

    // The closing just created, for an operator who may not open the Z-Bon history.
    zBonState.createdZBon?.let { created ->
        CreatedZBonDialog(
            zBon = created,
            compact = isCompact,
            language = language,
            onDismiss = { zBonViewModel.dismissCreatedZBon() },
            onPayOutTip = onPayOutTip?.let { pay -> { status -> pay(created.id, status) } },
            onReverseTipPayout = onReverseTipPayout?.let { reverse -> { status, record -> reverse(created.id, status, record) } },
        )
    }

    // The storno of a booked tip payout being confirmed.
    zBonState.tipReversalDraft?.let { draft ->
        TipReversalDialog(
            draft = draft,
            onReasonChange = zBonViewModel::updateTipReversalReason,
            onConfirm = { zBonViewModel.confirmTipReversal(currencyCode) },
            onDismiss = zBonViewModel::dismissTipReversal,
        )
    }

    // The tip payout being confirmed — from the history's detail or the just-created closing.
    zBonState.tipPayoutDraft?.let { draft ->
        TipPayoutDialog(
            draft = draft,
            onAmountChange = zBonViewModel::updateTipPayoutAmount,
            onConfirm = { zBonViewModel.confirmTipPayout(currencyCode) },
            onDismiss = zBonViewModel::dismissTipPayout,
            onMethodChange = zBonViewModel::setTipPayoutMethod,
        )
    }

    // Custom date range picker dialog
    if (uiState.showCustomDateRangeDialog) {
        CustomDateRangePickerDialog(
            language = language,
            onDismiss = { viewModel.dismissCustomDateRangeDialog() },
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

/**
 * Whether this principal may close the day — the till half of `ZBonRules`' `POST /api/z-bon` →
 * `ZBON_CLOSE`. Deliberately not `REPORT_ALL_READ`: reading every employee's figures and making a
 * day fiscally final are different authorities, and the auditor and accountant groups hold the
 * first without the second (T5-5).
 */
internal fun mayCreateZBon(policy: AccessPolicy): Boolean = policy.allows(Capability.ZBON_CLOSE)

/**
 * Whether this principal may open the Z-Bon history — the till half of `ZBonRules`, which gates
 * `/api/z-bon/recent`, `/{id}`, `/history` and `/{id}/pdf` on `ZBON_MANAGE`. Not `REPORT_ALL_READ`:
 * a permission group can hold the shop-wide report without the closing documents.
 */
internal fun mayReadZBonHistory(policy: AccessPolicy): Boolean = policy.allows(Capability.ZBON_MANAGE)

/**
 * Whether this principal may pay an employee's tips out of a closing — the till half of `ZBonRules`'
 * `POST /api/z-bon/{id}/tip-payouts` → `CASHBOOK_WRITE`. A cash-book capability, not a Z-Bon one:
 * the payout is a withdrawal from the fiscal Ladenkasse like any other.
 */
internal fun mayPayOutTips(policy: AccessPolicy): Boolean = policy.allows(Capability.CASHBOOK_WRITE)
