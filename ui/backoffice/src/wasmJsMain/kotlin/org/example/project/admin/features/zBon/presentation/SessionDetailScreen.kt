package org.example.project.admin.features.zBon.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.AdminSplitPane
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.ItemTransferHistoryDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.BillReceiptKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatShortDateTime
import org.example.project.ui.components.PosAdaptiveColumn
import org.example.project.ui.components.PosAdaptiveDataTable
import org.example.project.ui.components.PosBillDetailPanel
import org.example.project.ui.components.PosCardRole
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosTableAlign
import org.example.project.ui.components.PosTableColumn
import org.example.project.ui.components.PosTableMin
import org.example.project.ui.components.PosTableRow
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.components.listSummaryText
import org.example.project.ui.layout.PaneBreakpoints
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionDetailScreen(
    sessionId: Long,
    onBack: () -> Unit,
    // No default: the only caller (ZBonHubScreen) resolves this from the loaded ZBonResponseDto's
    // paymentSummary — a client-side "EUR" fallback here would silently mask that resolution.
    currencyCode: String,
    viewModel: SessionDetailViewModel = koinViewModel { parametersOf(sessionId) },
) {
    LaunchedEffect(sessionId) { viewModel.load() }

    val state = viewModel.state
    val language = LocalAppLanguage.current

    PosErrorWatcher(
        error = state.sessionError,
        title = str(AdminStringKeys.ZBonSessions),
        onClear = viewModel::clearError,
        retryAction = { viewModel.load() },
    )

    // For sub-tab errors (orders/cancellations/transfers/bills)
    val activeError =
        when (state.activeTab) {
            SessionDetailTab.ORDERS -> state.ordersError
            SessionDetailTab.CANCELLATIONS -> state.cancellationsError
            SessionDetailTab.TRANSFERS -> state.transfersError
            SessionDetailTab.BILLS -> state.billsError
        }
    if (activeError != null && state.sessionError == null) {
        PosErrorWatcher(
            error = activeError,
            title = "${str(AdminStringKeys.ZBonSessions)} ${str(CommonKeys.error)}",
            onClear = viewModel::clearError,
            retryAction = { viewModel.load() },
        )
    }

    Column(Modifier.fillMaxSize()) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PosIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = str(AdminStringKeys.NavBack),
                onClick = onBack,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                state.session?.let { session ->
                    Text(
                        text = "${str(AdminStringKeys.FloorPlanTableNumber)}: ${session.tableNumber}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val startStr = session.startTime?.let { it.toString().formatShortDateTime(language) } ?: ""
                    val endStr = session.endTime?.let { it.toString().formatShortDateTime(language) } ?: ""
                    Text(
                        text = if (endStr.isNotEmpty()) "$startStr – $endStr" else startStr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.sessionLoading && state.session == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            state.session?.let { session ->
                // Summary bar
                // Active bills only: the list shows every receipt, a Storno pair cancels out.
                val billsRevenue = state.billsRevenue.toString()

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SummaryChip(str(AdminStringKeys.FloorPlanTableNumber), session.tableNumber)
                    SummaryChip(str(AdminStringKeys.StaffFieldNumber), session.employeeNumber)
                    // Marked like the Sessions tab row and the session's receipts (admin21 FAIL-A21).
                    if (session.isTraining) {
                        SummaryChip(str(CommonKeys.status), str(BillReceiptKeys.trainingBadge))
                    }
                    SummaryChip(str(AdminStringKeys.ZBonBills), state.bills.size.toString())
                    SummaryChip(str(AdminStringKeys.DashboardZbonRevenue), formatMoneyForDisplay(billsRevenue, currencyCode))
                }

                // Tab row: four German labels do not fit a tab each below 600dp, so chips that wrap.
                val tabs =
                    listOf(
                        SessionDetailTab.ORDERS to "${str(AdminStringKeys.SessionOrders)} (${state.orders.size})",
                        SessionDetailTab.CANCELLATIONS to "${str(AdminStringKeys.SessionCancellations)} (${state.cancellations.size})",
                        SessionDetailTab.TRANSFERS to "${str(AdminStringKeys.SessionTransfers)} (${state.transfers.size})",
                        SessionDetailTab.BILLS to "${str(AdminStringKeys.SessionBills)} (${state.bills.size})",
                    )

                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < PaneBreakpoints.PHONE_MAX) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            tabs.forEach { (tab, label) ->
                                FilterChip(
                                    selected = state.activeTab == tab,
                                    onClick = { viewModel.selectTab(tab) },
                                    label = { Text(label) },
                                    modifier = Modifier.heightIn(min = 40.dp),
                                )
                            }
                        }
                    } else {
                        PrimaryTabRow(selectedTabIndex = state.activeTab.ordinal) {
                            tabs.forEach { (tab, label) ->
                                Tab(
                                    selected = state.activeTab == tab,
                                    onClick = { viewModel.selectTab(tab) },
                                    text = { Text(label) },
                                )
                            }
                        }
                    }
                }

                // Master-detail: side by side from 840dp, a list-then-detail drill below it.
                val detailOpen = hasOpenDetail(state)
                AdminSplitPane(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    // Below 840dp the fixed rail would leave the detail ~220dp: drill list-then-detail
                    // instead; from 840dp the two show side by side.
                    minWidthForSplit = 840.dp,
                    railWidth = 360.dp,
                    detailOpen = detailOpen,
                    onCloseDetail = viewModel::clearSelection,
                    list = {
                        // Left: list
                        val leftModifier = Modifier.fillMaxSize()

                        when (state.activeTab) {
                            SessionDetailTab.ORDERS -> {
                                OrdersTab(
                                    orders = state.orders,
                                    isLoading = state.ordersLoading,
                                    selectedId = state.selectedOrderId,
                                    onSelect = viewModel::selectOrder,
                                    currencyCode = currencyCode,
                                    modifier = leftModifier,
                                )
                            }
                            SessionDetailTab.CANCELLATIONS -> {
                                CancellationsTab(
                                    cancellations = state.cancellations,
                                    isLoading = state.cancellationsLoading,
                                    selectedId = state.selectedCancellationId,
                                    onSelect = viewModel::selectCancellation,
                                    currencyCode = currencyCode,
                                    modifier = leftModifier,
                                )
                            }
                            SessionDetailTab.TRANSFERS -> {
                                TransfersTab(
                                    transfers = state.transfers,
                                    isLoading = state.transfersLoading,
                                    subTab = state.transferSubTab,
                                    selectedId = state.selectedTransferId,
                                    onSelect = viewModel::selectTransfer,
                                    onSubTabChange = viewModel::selectTransferDirection,
                                    currencyCode = currencyCode,
                                    modifier = leftModifier,
                                )
                            }
                            SessionDetailTab.BILLS -> {
                                if (state.billsLoading) {
                                    Box(leftModifier, contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                } else {
                                    SessionBillsTab(
                                        bills = state.bills,
                                        selectedBill = state.selectedBill,
                                        onSelect = viewModel::selectBill,
                                        currencyCode = currencyCode,
                                        modifier = leftModifier,
                                    )
                                }
                            }
                        }
                    },
                    detail = {
                        // Right: detail panel
                        val rightModifier = Modifier.fillMaxSize()

                        when {
                            state.activeTab == SessionDetailTab.ORDERS && state.selectedOrderId != null -> {
                                val order = state.orders.find { it.orderId == state.selectedOrderId }
                                if (order != null) {
                                    OrderDetailPanel(order = order, currencyCode = currencyCode, modifier = rightModifier)
                                } else {
                                    PlaceholderPanel(rightModifier)
                                }
                            }
                            state.activeTab == SessionDetailTab.CANCELLATIONS && state.selectedCancellationId != null -> {
                                val cancellation = state.cancellations.find { it.cancellationId == state.selectedCancellationId }
                                if (cancellation != null) {
                                    CancellationDetailPanel(cancellation = cancellation, currencyCode = currencyCode, modifier = rightModifier)
                                } else {
                                    PlaceholderPanel(rightModifier)
                                }
                            }
                            state.activeTab == SessionDetailTab.TRANSFERS && state.selectedTransferId != null -> {
                                val transfer = state.transfers.find { it.transferId == state.selectedTransferId }
                                if (transfer != null) {
                                    TransferDetailPanel(transfer = transfer, currencyCode = currencyCode, modifier = rightModifier)
                                } else {
                                    PlaceholderPanel(rightModifier)
                                }
                            }
                            state.activeTab == SessionDetailTab.BILLS && state.selectedBill != null -> {
                                val bill = state.selectedBill
                                PosBillDetailPanel(
                                    bill = bill,
                                    tableNumber = bill.tableNumber,
                                    currency = currencyCode,
                                    // Related-bill links open the linked bill in this same panel.
                                    onOpenLinkedBill = viewModel::openLinkedBill,
                                    backTo = state.billTrail.backTo,
                                    onBack = viewModel::backToPreviousBill,
                                    modifier = rightModifier.verticalScroll(rememberScrollState()).padding(8.dp),
                                )
                            }
                            else -> PlaceholderPanel(rightModifier)
                        }
                    },
                )
            }
        }
    }
}

/** Whether the active tab has an element selected that its detail panel can actually show. */
private fun hasOpenDetail(state: SessionDetailState): Boolean =
    when (state.activeTab) {
        SessionDetailTab.ORDERS -> state.orders.any { it.orderId == state.selectedOrderId }
        SessionDetailTab.CANCELLATIONS -> state.cancellations.any { it.cancellationId == state.selectedCancellationId }
        SessionDetailTab.TRANSFERS -> state.transfers.any { it.transferId == state.selectedTransferId }
        SessionDetailTab.BILLS -> state.selectedBill != null
    }

@Composable
private fun SummaryChip(
    label: String,
    value: String,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PlaceholderPanel(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = str(AdminStringKeys.SessionSelectItemHint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Tab content composables ────────────────────────────────────────────────

@Composable
private fun OrdersTab(
    orders: List<OrderDto.OrderResponseDto>,
    isLoading: Boolean,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    currencyCode: String,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    if (isLoading) {
        Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        val columns =
            listOf(
                PosAdaptiveColumn(
                    PosTableColumn(title = "#", weight = 1.15f, align = PosTableAlign.Start, minWidth = PosTableMin.Id, nowrap = true),
                    PosCardRole.Title,
                ),
                PosAdaptiveColumn(
                    PosTableColumn(title = str(CommonKeys.time), weight = 1.15f, minWidth = PosTableMin.DateTime, nowrap = true),
                    PosCardRole.Detail,
                ),
                PosAdaptiveColumn(PosTableColumn(title = str(AdminStringKeys.FloorPlanTableNumber), weight = 0.9f, nowrap = true), PosCardRole.Detail),
                PosAdaptiveColumn(
                    PosTableColumn(title = str(CommonKeys.total), weight = 0.95f, align = PosTableAlign.End, minWidth = PosTableMin.Money, nowrap = true),
                    PosCardRole.Trailing,
                ),
            )
        val rows =
            orders.map { order ->
                PosTableRow(
                    cells =
                        listOf(
                            order.orderNumber,
                            order.orderTime.formatShortDateTime(language),
                            order.tableNumber,
                            formatMoneyForDisplay(order.orderTotal, currencyCode),
                        ),
                    onClick = { onSelect(order.orderId) },
                )
            }
        PosAdaptiveDataTable(columns = columns, rows = rows, modifier = modifier.verticalScroll(rememberScrollState()))
    }
}

@Composable
private fun CancellationsTab(
    cancellations: List<OrderItemDto.CancellationResponseDto>,
    isLoading: Boolean,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    currencyCode: String,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    if (isLoading) {
        Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else if (cancellations.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                text = str(AdminStringKeys.SessionNoCancellations),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        val columns =
            listOf(
                PosAdaptiveColumn(PosTableColumn(title = "#", weight = 0.95f, align = PosTableAlign.End, nowrap = true), PosCardRole.Title),
                PosAdaptiveColumn(PosTableColumn(title = str(AdminStringKeys.ProductColName), weight = 1.25f, minWidth = 128.dp), PosCardRole.Detail),
                PosAdaptiveColumn(
                    PosTableColumn(title = str(CommonKeys.time), weight = 1.25f, minWidth = PosTableMin.DateTime, nowrap = true),
                    PosCardRole.Detail,
                ),
                PosAdaptiveColumn(
                    PosTableColumn(title = str(CommonKeys.amount), weight = 1f, align = PosTableAlign.End, minWidth = PosTableMin.Money, nowrap = true),
                    PosCardRole.Trailing,
                ),
            )
        val rows =
            cancellations.map { cancellation ->
                val productName = cancellation.orderItemId.product.productName
                PosTableRow(
                    cells =
                        listOf(
                            cancellation.cancellationId.toString(),
                            productName,
                            cancellation.createdAt.formatShortDateTime(language),
                            formatMoneyForDisplay(cancellation.canceledAmount, currencyCode),
                        ),
                    onClick = { onSelect(cancellation.cancellationId) },
                )
            }
        PosAdaptiveDataTable(columns = columns, rows = rows, modifier = modifier.verticalScroll(rememberScrollState()))
    }
}

@Composable
private fun TransfersTab(
    transfers: List<ItemTransferHistoryDto.ItemTransferHistoryResponseDto>,
    isLoading: Boolean,
    subTab: TransferDirection,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onSubTabChange: (TransferDirection) -> Unit,
    currencyCode: String,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current

    Column(modifier = modifier) {
        // Sub-tab: IN / OUT
        Row(Modifier.fillMaxWidth()) {
            Tab(
                selected = subTab == TransferDirection.IN,
                onClick = { onSubTabChange(TransferDirection.IN) },
                text = { Text(str(AdminStringKeys.SessionTransferIn)) },
                modifier = Modifier.weight(1f),
            )
            Tab(
                selected = subTab == TransferDirection.OUT,
                onClick = { onSubTabChange(TransferDirection.OUT) },
                text = { Text(str(AdminStringKeys.SessionTransferOut)) },
                modifier = Modifier.weight(1f),
            )
        }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val filtered =
                transfers.filter {
                    (subTab == TransferDirection.IN && it.direction == "IN") ||
                        (subTab == TransferDirection.OUT && it.direction == "OUT")
                }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = str(AdminStringKeys.SessionNoTransfers),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val columns =
                    listOf(
                        PosAdaptiveColumn(PosTableColumn(title = "#", weight = 0.9f, align = PosTableAlign.End, nowrap = true), PosCardRole.Title),
                        PosAdaptiveColumn(PosTableColumn(title = str(AdminStringKeys.FloorPlanTableNumber), weight = 0.9f, nowrap = true), PosCardRole.Detail),
                        PosAdaptiveColumn(
                            PosTableColumn(
                                title = str(CommonKeys.amount),
                                weight = 1.15f,
                                align = PosTableAlign.End,
                                minWidth = PosTableMin.Money,
                                nowrap = true,
                            ),
                            PosCardRole.Trailing,
                        ),
                        PosAdaptiveColumn(
                            PosTableColumn(title = str(CommonKeys.time), weight = 1.15f, minWidth = PosTableMin.DateTime, nowrap = true),
                            PosCardRole.Detail,
                        ),
                    )
                val rows =
                    filtered.map { transfer ->
                        val tableLabel = "${transfer.fromTableNumber} -> ${transfer.toTableNumber}"
                        PosTableRow(
                            cells =
                                listOf(
                                    transfer.transferId.toString(),
                                    tableLabel,
                                    formatMoneyForDisplay(transfer.totalAmount, currencyCode),
                                    transfer.transferTime.formatShortDateTime(language),
                                ),
                            onClick = { onSelect(transfer.transferId) },
                        )
                    }
                PosAdaptiveDataTable(columns = columns, rows = rows, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
private fun SessionBillsTab(
    bills: List<BillDto.BillResponseDto>,
    selectedBill: BillDto.BillResponseDto?,
    onSelect: (BillDto.BillResponseDto?) -> Unit,
    currencyCode: String,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    val columns =
        listOf(
            // 1.5 not 1.2: in the 45 % master pane "Rechnungsnummer" wrapped mid-word (QA admin18 NOTE-22).
            PosAdaptiveColumn(PosTableColumn(title = str(ReportsKeys.billNumber), weight = 1.5f, minWidth = PosTableMin.Id, nowrap = true), PosCardRole.Title),
            PosAdaptiveColumn(PosTableColumn(title = str(BillReceiptKeys.relationSectionTitle), weight = 1.3f, minWidth = 128.dp), PosCardRole.Detail),
            PosAdaptiveColumn(PosTableColumn(title = str(CommonKeys.time), weight = 1.3f, minWidth = PosTableMin.DateTime, nowrap = true), PosCardRole.Detail),
            PosAdaptiveColumn(
                PosTableColumn(title = str(CommonKeys.total), weight = 1f, align = PosTableAlign.End, minWidth = PosTableMin.Money, nowrap = true),
                PosCardRole.Trailing,
            ),
        )
    val rows =
        bills.map { bill ->
            PosTableRow(
                cells =
                    listOf(
                        bill.billNumber,
                        // "Training", "Zahlartwechsel zu RE-…", "Storno zu RE-…", "Storniert", …
                        bill.listSummaryText(),
                        bill.endTime.formatShortDateTime(language),
                        formatMoneyForDisplay(bill.paidAmount, currencyCode),
                    ),
                onClick = { onSelect(if (selectedBill?.billId == bill.billId) null else bill) },
            )
        }
    PosAdaptiveDataTable(columns = columns, rows = rows, modifier = modifier.verticalScroll(rememberScrollState()))
}
