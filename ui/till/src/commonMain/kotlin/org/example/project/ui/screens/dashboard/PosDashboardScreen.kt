package org.example.project.ui.screens.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.launch
import org.example.project.core.auth.LocalPermissionManager
import org.example.project.core.auth.LocalSettingManager
import org.example.project.core.auth.PermissionManager
import org.example.project.core.data.domain.service.PreferenceSyncService
import org.example.project.core.data.models.RTable
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.rules.TableAccessRuleEngine
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.components.notification.GlobalOverlayViewModel
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.navigation.BackHandler
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.DashboardKeys
import org.example.project.core.presentation.strings.keys.RulesKeys
import org.example.project.core.presentation.strings.keys.VoucherKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalLanguageManager
import org.example.project.core.utils.LocalNotificationViewModel
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.features.dashboard.presentation.state.PosDashboardEvent
import org.example.project.features.dashboard.presentation.state.TableOperationsUiState
import org.example.project.features.dashboard.presentation.viewmodel.TableGridViewModel
import org.example.project.features.dashboard.presentation.viewmodel.TableOperationsViewModel
import org.example.project.features.notifications.presentation.viewmodel.NotificationUiEvent
import org.example.project.features.notifications.presentation.viewmodel.NotificationViewModel
import org.example.project.ui.auth.holds
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosSuccessWatcher
import org.example.project.ui.screens.dashboard.components.CustomerAddressSelectionPanel
import org.example.project.ui.screens.dashboard.dialogs.PosDeleteTableDialog
import org.example.project.ui.screens.dashboard.dialogs.PosEmployeePickerDialog
import org.example.project.ui.screens.dashboard.dialogs.PosTableLookupDialog
import org.example.project.ui.screens.dashboard.dialogs.PosTransferTableDialog
import org.example.project.ui.screens.voucher.components.VoucherDialog
import org.example.project.ui.screens.voucher.components.VoucherDialogMode
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Main POS Dashboard screen showing table grid or floor plan.
 *
 * For phone/compact: Shows PosDashboardGrid with PosDashboardTopBar.
 * For tablet/desktop in restaurant mode: Shows FloorPlanScreen.
 * Long press on phone shows PosTableDetailScreen as full-screen overlay.
 * Long press on tablet shows TableActionsBottomSheet (Phase 2 will add right panel).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosDashboardScreen(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    gridViewModel: TableGridViewModel = koinViewModel(),
    operationsViewModel: TableOperationsViewModel = koinViewModel(),
    onBackOnDashboard: () -> Unit,
    onNotificationsClick: () -> Unit = {},
) {
    val windowSize = LocalWindowSizeClass.current
    val scaffoldFocusRequester = remember { FocusRequester() }

    val gridState by gridViewModel.uiState.collectAsState()
    val opsState by operationsViewModel.uiState.collectAsState()
    val onEvent = operationsViewModel::onEvent

    val overlay = LocalGlobalOverlayViewModel.current
    val notificationViewModel = LocalNotificationViewModel.current
    val settingManager = LocalSettingManager.current
    val languageManager = LocalLanguageManager.current
    val currentLanguage = languageManager.getCurrentLanguage()
    val permissionManager = LocalPermissionManager.current

    val tableMode = settingManager.tablePlanMode.collectAsState(true).value
    val voucherEnabled = settingManager.voucherEnabled.collectAsState(false).value

    val ruleEngine: TableAccessRuleEngine = koinInject()
    val preferenceSyncService: PreferenceSyncService = koinInject()
    val refreshScope = rememberCoroutineScope()

    val isPhone = windowSize.isCompact()
    val isPhoneOrGridMode = isPhone || !tableMode

    BackHandler {
        if (!opsState.anyOverlayOpen) {
            onBackOnDashboard()
        }
        onEvent(PosDashboardEvent.DismissOverlay)
    }

    // No displayMode: the bus routes by what the error carries — field errors and 5xx escalate to
    // PosErrorDialog, everything else toasts. The override here was inherited from the hand-rolled
    // version this replaced ("dashboard errors were always modal"), not a requirement of this screen.
    PosErrorWatcher(
        error = opsState.error,
        title = getString(CommonKeys.error, currentLanguage),
        onClear = { operationsViewModel.clearError() },
    )

    PosSuccessWatcher(
        message = opsState.successMessage,
        title = getString(DashboardKeys.successTitle, currentLanguage),
        onClear = { operationsViewModel.clearSuccessMessage() },
    )

    LaunchedEffect(opsState.anyOverlayOpen) {
        if (!opsState.anyOverlayOpen) scaffoldFocusRequester.requestFocus()
    }

    // Another device opened, ordered on or paid a table: show its new status and amount live.
    // Without this the wasm till/station (no local cache, so no cache write-through) kept the old
    // grid until the manual refresh (F55).
    LaunchedEffect(Unit) {
        notificationViewModel.notificationEvents.collect { event ->
            if (event is NotificationUiEvent.TableStatusChanged) gridViewModel.onTableStatusChanged(event)
        }
    }

    val handleTableClick: (RTable) -> Unit = { table ->
        when (val access = ruleEngine.checkTableAccess(table)) {
            TableAccessRuleEngine.AccessResult.Allowed ->
                PosDashboardNavHelper.navigateToTableOrder(navController, table.id)

            TableAccessRuleEngine.AccessResult.RequiresEmployeeSelection -> {
                onEvent(PosDashboardEvent.SetPendingTable(table))
                onEvent(PosDashboardEvent.ShowEmployeeSelectionDialog(true))
            }

            is TableAccessRuleEngine.AccessResult.Denied -> overlay.warnTableDenied(access, currentLanguage)
        }
    }

    val handleTableLongClick: (RTable) -> Unit = { table ->
        val status =
            try {
                TableStatus.valueOf(table.tableStatus)
            } catch (_: Exception) {
                TableStatus.FREE
            }
        if (status == TableStatus.OCCUPIED || status == TableStatus.RESERVED) {
            onEvent(PosDashboardEvent.SelectTable(table))
            onEvent(PosDashboardEvent.ShowDetailPanel(true))
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .focusRequester(scaffoldFocusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Enter &&
                        event.type == KeyEventType.KeyDown &&
                        !opsState.anyOverlayOpen &&
                        !opsState.showCustomerPanel
                    ) {
                        onEvent(PosDashboardEvent.ShowTableNumberDialog(true))
                        true
                    } else {
                        false
                    }
                },
    ) {
        if (isPhoneOrGridMode) {
            Scaffold(
                topBar = {
                    PosDashboardTopBar(
                        onRefreshClick = {
                            gridViewModel.refresh()
                            notificationViewModel.connectToNotifications(true)
                            // Re-sync so a newly uploaded shop logo shows without a restart; failure is non-fatal.
                            refreshScope.launch { runCatchingApi { preferenceSyncService.syncPreferences() } }
                        },
                        onNotificationsClick = onNotificationsClick,
                        compact = isPhone,
                    )
                },
            ) { innerPadding ->

                PosDashboardGrid(
                    tables = gridState.tablesByType,
                    selectedLocationTypeId = gridState.selectedLocationTypeId,
                    locationTypes = gridState.locationTypes,
                    isLoading = gridState.isLoading,
                    onLocationTypeSelected = gridViewModel::selectTableType,
                    onTableClick = handleTableClick,
                    onTableLongClick = handleTableLongClick,
                    modifier = Modifier.padding(innerPadding),
                )
            }

            // FABs
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FloatingActionButton(
                    onClick = { onEvent(PosDashboardEvent.ShowTableNumberDialog(true)) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                }
            }
        } else {
            // Tablet/Desktop: Floor plan with right detail panel
            PosFloorPlanScreen(
                onTableClick = handleTableClick,
                onTableLongClick = handleTableLongClick,
                modifier = Modifier.fillMaxSize(),
                onRefreshClick = {
                    notificationViewModel.connectToNotifications(true)
                },
                onTableAction = { table, action ->
                    onEvent(PosDashboardEvent.SelectTable(table))
                    when (action) {
                        "order" -> PosDashboardNavHelper.navigateToTableOrder(navController, table.id)
                        "billing" -> PosDashboardNavHelper.navigateToTableBilling(navController, table.id)
                        "history" -> PosDashboardNavHelper.navigateToTableHistory(navController, table.id)
                        "transfer" -> onEvent(PosDashboardEvent.ShowTransferDialog(true))
                        "assign" -> {
                            operationsViewModel.loadEmployees()
                            onEvent(PosDashboardEvent.ShowEmployeeSelectionDialog(true))
                        }

                        "delete" -> onEvent(PosDashboardEvent.ShowDeleteDialog(true))
                    }
                },
            )
        }

        // Sell-voucher FAB — a dashboard-level action, reachable without a selected table.
        // Offset to the opposite corner from the phone/grid FAB row so the two never overlap.
        // Capability-gated like the table grid's voucher action: the order station's principal holds
        // no VOUCHER_WRITE (POST /api/vouchers → 403), so the station never offers a sale it cannot
        // book (emu20 FAIL-B). The server refuses regardless.
        if (voucherEnabled && holds(Capability.VOUCHER_WRITE)) {
            FloatingActionButton(
                onClick = { onEvent(PosDashboardEvent.ShowVoucherDialog(true, VoucherDialogMode.COUNTER_SALE)) },
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            ) {
                Icon(Icons.Default.CardGiftcard, contentDescription = str(VoucherKeys.sellVoucher))
            }
        }

        // Phone detail panel overlay — slides in from right
        val detailTable = opsState.selectedTable
        AnimatedVisibility(
            visible = opsState.showDetailPanel && detailTable != null,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (!isPhone) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        onEvent(PosDashboardEvent.ShowDetailPanel(false))
                                        onEvent(PosDashboardEvent.SelectTable(null))
                                    },
                                ),
                    )
                }
                detailTable?.let { table ->
                    PosTableDetailScreen(
                        modifier =
                            if (isPhone) {
                                Modifier.fillMaxSize()
                            } else {
                                Modifier.fillMaxHeight().width(400.dp).align(Alignment.CenterEnd)
                            },
                        table = table,
                        onBack = {
                            onEvent(PosDashboardEvent.ShowDetailPanel(false))
                            onEvent(PosDashboardEvent.SelectTable(null))
                        },
                        onNewOrder = {
                            onEvent(PosDashboardEvent.ShowDetailPanel(false))
                            PosDashboardNavHelper.navigateToTableOrder(navController, table.id)
                        },
                        onCreateBill = {
                            onEvent(PosDashboardEvent.ShowDetailPanel(false))
                            PosDashboardNavHelper.navigateToTableBilling(navController, table.id)
                        },
                        onRequestBilling = {
                            operationsViewModel.requestBilling(table.id) {
                                onEvent(PosDashboardEvent.ShowDetailPanel(false))
                                onEvent(PosDashboardEvent.SelectTable(null))
                            }
                        },
                        onTransfer = {
                            onEvent(PosDashboardEvent.ShowDetailPanel(false))
                            onEvent(PosDashboardEvent.ShowTransferDialog(true))
                        },
                        onHistory = {
                            onEvent(PosDashboardEvent.ShowDetailPanel(false))
                            PosDashboardNavHelper.navigateToTableHistory(navController, table.id)
                        },
                        onAssignCustomer = {
                            operationsViewModel.toggleCustomerPanel(true, table)
                        },
                        onAddVoucher = {
                            onEvent(PosDashboardEvent.ShowDetailPanel(false))
                            onEvent(PosDashboardEvent.ShowVoucherDialog(true, VoucherDialogMode.TABLE_SESSION))
                        },
                        onDelete = {
                            onEvent(PosDashboardEvent.ShowDetailPanel(false))
                            onEvent(PosDashboardEvent.ShowDeleteDialog(true))
                        },
                    )
                }
            }
        }

        // Dialogs and overlays (shared across both modes)
        DashboardDialogs(
            opsState = opsState,
            onEvent = onEvent,
            voucherEnabled = voucherEnabled,
            currentLanguage = currentLanguage,
            windowSize = windowSize,
            navController = navController,
            operationsViewModel = operationsViewModel,
            notificationViewModel = notificationViewModel,
            ruleEngine = ruleEngine,
            permissionManager = permissionManager,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardDialogs(
    opsState: TableOperationsUiState,
    onEvent: (PosDashboardEvent) -> Unit,
    voucherEnabled: Boolean,
    currentLanguage: Language,
    windowSize: WindowSizeClass,
    navController: NavHostController,
    operationsViewModel: TableOperationsViewModel,
    notificationViewModel: NotificationViewModel,
    ruleEngine: TableAccessRuleEngine,
    permissionManager: PermissionManager,
) {
    val overlay = LocalGlobalOverlayViewModel.current

    // Table Number Lookup Dialog
    PosTableLookupDialog(
        isVisible = opsState.showTableNumberDialog,
        language = currentLanguage,
        searchResults = opsState.tableSearchResults,
        onSearchQueryChanged = operationsViewModel::searchTablesByNumber,
        onDismiss = {
            onEvent(PosDashboardEvent.ShowTableNumberDialog(false))
            operationsViewModel.clearTableSearchResults()
        },
        onTableSelected = { table ->
            onEvent(PosDashboardEvent.ShowTableNumberDialog(false))
            operationsViewModel.clearTableSearchResults()
            when (val access = ruleEngine.checkTableAccess(table)) {
                TableAccessRuleEngine.AccessResult.Allowed ->
                    PosDashboardNavHelper.navigateToTableOrder(navController, table.id)

                TableAccessRuleEngine.AccessResult.RequiresEmployeeSelection -> {
                    onEvent(PosDashboardEvent.SetPendingTable(table))
                    onEvent(PosDashboardEvent.ShowEmployeeSelectionDialog(true))
                }

                is TableAccessRuleEngine.AccessResult.Denied -> overlay.warnTableDenied(access, currentLanguage)
            }
        },
        onTableDoubleEnter = { table ->
            onEvent(PosDashboardEvent.ShowTableNumberDialog(false))
            operationsViewModel.clearTableSearchResults()
            when (val access = ruleEngine.checkTableAccess(table)) {
                TableAccessRuleEngine.AccessResult.Allowed -> {
                    onEvent(PosDashboardEvent.SelectTable(table))
                    // Show detail panel (phone) — tablet handles via PosFloorPlanScreen internally
                    onEvent(PosDashboardEvent.ShowDetailPanel(true))
                }

                TableAccessRuleEngine.AccessResult.RequiresEmployeeSelection -> {
                    onEvent(PosDashboardEvent.SetPendingTable(table))
                    onEvent(PosDashboardEvent.ShowEmployeeSelectionDialog(true))
                }

                is TableAccessRuleEngine.AccessResult.Denied -> overlay.warnTableDenied(access, currentLanguage)
            }
        },
        onTableLongPress = { table ->
            val status =
                try {
                    TableStatus.valueOf(table.tableStatus)
                } catch (_: Exception) {
                    TableStatus.FREE
                }
            if (status == TableStatus.OCCUPIED || status == TableStatus.RESERVED) {
                onEvent(PosDashboardEvent.ShowTableNumberDialog(false))
                operationsViewModel.clearTableSearchResults()
                onEvent(PosDashboardEvent.SelectTable(table))
                onEvent(PosDashboardEvent.ShowDetailPanel(true))
            }
        },
    )

    // Transfer dialog
    if (opsState.showTransferDialog && opsState.selectedTable != null) {
        val selectedTable = opsState.selectedTable

        LaunchedEffect(Unit) {
            operationsViewModel.loadEmployees()
        }

        val currentEmployeeNumber by permissionManager.currentEmployeeNumber.collectAsState()
        PosTransferTableDialog(
            sourceTable = selectedTable,
            employees = opsState.employees,
            currentEmployeeNumber = currentEmployeeNumber,
            canSelectSelf = ruleEngine.canSelectSelf(),
            searchResults = opsState.tableSearchResults,
            onSearchQueryChanged = operationsViewModel::searchTablesByNumber,
            onTransfer = { targetTable, employeeNumber, reason ->
                operationsViewModel.transferTable(
                    sourceTableId = selectedTable.id,
                    targetTableId = targetTable?.id ?: selectedTable.id,
                    employeeNumber = employeeNumber,
                    reason = reason,
                    onSuccess = {
                        onEvent(PosDashboardEvent.ShowTransferDialog(false))
                        onEvent(PosDashboardEvent.SelectTable(null))
                        operationsViewModel.clearTableSearchResults()
                    },
                )
            },
            onDismiss = {
                onEvent(PosDashboardEvent.ShowTransferDialog(false))
                onEvent(PosDashboardEvent.SelectTable(null))
                operationsViewModel.clearTableSearchResults()
            },
        )
    }

    // Delete confirmation dialog
    if (opsState.showDeleteDialog && opsState.selectedTable != null) {
        val selectedTable = opsState.selectedTable
        PosDeleteTableDialog(
            table = selectedTable,
            onConfirm = { reason ->
                operationsViewModel.deleteTableSession(
                    tableId = selectedTable.id,
                    reason = reason,
                    onSuccess = {
                        onEvent(PosDashboardEvent.ShowDeleteDialog(false))
                        onEvent(PosDashboardEvent.SelectTable(null))
                    },
                )
            },
            onDismiss = {
                onEvent(PosDashboardEvent.ShowDeleteDialog(false))
                onEvent(PosDashboardEvent.SelectTable(null))
            },
        )
    }

    // Customer address selection panel
    Box(modifier = Modifier.fillMaxSize()) {
        CustomerAddressSelectionPanel(
            modifier = Modifier.align(Alignment.TopEnd),
            windowSize = windowSize,
            uiState = opsState,
            currentLanguage = currentLanguage,
            onEvent = { event ->
                operationsViewModel.onCustomerPanelEvent(event)
            },
        )
    }

    // Voucher Dialog
    VoucherDialog(
        isVisible = opsState.showVoucherDialog,
        mode = opsState.voucherDialogMode,
        selectedTable = opsState.selectedTable,
        language = currentLanguage,
        isVoucherEnabled = voucherEnabled,
        onDismiss = {
            onEvent(PosDashboardEvent.ShowVoucherDialog(false))
            onEvent(PosDashboardEvent.SelectTable(null))
        },
        onVoucherCreated = { _ -> },
    )

    // Employee selection dialog
    if (opsState.showEmployeeSelectionDialog && opsState.pendingTable != null) {
        val pendingTable = opsState.pendingTable
        val currentEmployeeNumber by permissionManager.currentEmployeeNumber.collectAsState()
        PosEmployeePickerDialog(
            availableEmployees = opsState.employees,
            currentEmployeeNumber = currentEmployeeNumber,
            canSelectSelf = ruleEngine.canSelectSelf(),
            onEmployeeSelected = { employee ->
                onEvent(PosDashboardEvent.ShowEmployeeSelectionDialog(false))
                onEvent(PosDashboardEvent.SetPendingTable(null))
                PosDashboardNavHelper.navigateToTableOrder(navController, pendingTable.id, employee.employeeNumber)
            },
            onDismiss = {
                onEvent(PosDashboardEvent.ShowEmployeeSelectionDialog(false))
                onEvent(PosDashboardEvent.SetPendingTable(null))
            },
            onLoadEmployees = operationsViewModel::loadEmployees,
        )
    }
}

/**
 * Tells the operator why a table did not open (spec 2026-09-14 §5.2). A denial without a reason — the
 * training gates, a principal without TABLE_WRITE — stays silent, as it always has.
 */
private fun GlobalOverlayViewModel.warnTableDenied(
    denied: TableAccessRuleEngine.AccessResult.Denied,
    language: Language,
) {
    val reason = denied.reason ?: return
    showWarning(
        title = getString(RulesKeys.stationTableDeniedTitle, language),
        message = getString(reason, language),
    )
}
