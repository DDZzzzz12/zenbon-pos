package org.example.project.ui.screens.dashboard.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TableOrderHistoryKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.features.dashboard.presentation.state.TableOrderHistoryUiState
import org.example.project.features.dashboard.presentation.state.TimelineEntry
import org.example.project.features.dashboard.presentation.viewmodel.TableOrderHistoryViewModel
import org.example.project.ui.auth.holds
import org.example.project.ui.components.PosBillDetailPanel
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosMarqueeText
import org.example.project.ui.components.PosNavIconButton
import org.example.project.ui.components.PosSegmentedButtonRow
import org.koin.compose.viewmodel.koinViewModel

/**
 * Main entry point for the table history screen.
 *
 * Displays order and bill history for a specific table with adaptive layout:
 * - COMPACT: Single column with segmented tab toggle
 * - MEDIUM/EXPANDED: List-detail split with order list (left) and detail panel (right)
 *
 * @param tableId The table ID to show history for
 * @param onNavigateBack Callback to navigate back
 * @param modifier Modifier for the root element
 * @param viewModel ViewModel managing order/bill state
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosTableHistoryScreen(
    tableId: Long,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TableOrderHistoryViewModel = koinViewModel(),
) {
    val windowSize = LocalWindowSizeClass.current
    val uiState by viewModel.uiState.collectAsState()

    PosErrorWatcher(
        error = uiState.error,
        title = str(CommonKeys.error),
        onClear = { viewModel.clearError() },
    )

    // 0 = Orders, 1 = Bills. The Bills tab needs BILL_READ (GET /api/v1/bills/table-session/{id});
    // an order station's mask has none, so it sees the orders only — "view existing orders".
    val canReadBills = holds(Capability.BILL_READ)
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(tableId, canReadBills) {
        viewModel.loadOrderHistory(tableId)
        if (canReadBills) viewModel.loadBills(tableId)
    }

    val isCompact = windowSize.isCompact()

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        val tableLabel = uiState.tableNumber ?: tableId.toString()
                        PosMarqueeText(
                            text = "${str(TableOrderHistoryKeys.title)} - T-$tableLabel",
                        )
                    },
                    navigationIcon = {
                        PosNavIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = str(CommonKeys.back),
                            onClick = onNavigateBack,
                        )
                    },
                )
            },
        ) { paddingValues ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
            ) {
                // Segmented toggle: Orders / Bills. Withheld without BILL_READ — a one-option
                // toggle is noise, and the second option could only ever show a 403.
                if (canReadBills) {
                    PosSegmentedButtonRow(
                        options = listOf(0, 1),
                        selectedOption = selectedTab,
                        onOptionSelected = { selectedTab = it },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) { tab ->
                        if (tab == 0) str(TableOrderHistoryKeys.ordersTab) else str(TableOrderHistoryKeys.billsTab)
                    }
                }

                // Content area
                when {
                    uiState.isLoading || uiState.isLoadingBills -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    else -> {
                        if (isCompact) {
                            CompactContent(
                                selectedTab = selectedTab,
                                uiState = uiState,
                                viewModel = viewModel,
                            )
                        } else {
                            ExpandedContent(
                                selectedTab = selectedTab,
                                uiState = uiState,
                                viewModel = viewModel,
                            )
                        }
                    }
                }
            }
        }

        // Full-screen bill detail overlay (compact only) — renders ON TOP of everything
        if (isCompact) {
            val selectedBill = uiState.selectedBill
            AnimatedVisibility(
                visible = selectedBill != null,
                enter = slideInHorizontally(initialOffsetX = { it }),
                exit = slideOutHorizontally(targetOffsetX = { it }),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = {
                                    PosMarqueeText(
                                        text = str(TableOrderHistoryKeys.billNumber, selectedBill?.billNumber ?: ""),
                                    )
                                },
                                navigationIcon = {
                                    PosNavIconButton(
                                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = str(CommonKeys.back),
                                        onClick = { viewModel.selectBill(null) },
                                    )
                                },
                            )
                        },
                    ) { innerPadding ->
                        selectedBill?.let {
                            PosBillDetailPanel(
                                bill = selectedBill,
                                tableNumber = uiState.tableNumber ?: uiState.tableId.toString(),
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(innerPadding),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact layout: single column list of orders or bills.
 *
 * Orders: each card has an expand/collapse toggle for inline item viewing.
 * Bills: tapping a bill opens a full-screen overlay with PosBillDetailPanel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactContent(
    selectedTab: Int,
    uiState: TableOrderHistoryUiState,
    viewModel: TableOrderHistoryViewModel,
) {
    // Track which order cards are expanded (by orderId)
    var expandedOrderIds by remember { mutableStateOf(setOf<Long>()) }
    var expandedTransferIds by remember { mutableStateOf(setOf<Long>()) }

    // Compact bill detail state — driven by selectedBill in uiState

    when (selectedTab) {
        0 -> {
            if (!uiState.hasTimelineEntries) {
                EmptyState(message = str(TableOrderHistoryKeys.noOrders))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        uiState.timeline,
                        key = { entry ->
                            when (entry) {
                                is TimelineEntry.OrderEntry -> "order_${entry.order.orderId}"
                                is TimelineEntry.TransferEntry -> "transfer_${entry.transfer.transferId}"
                            }
                        },
                    ) { entry ->
                        when (entry) {
                            is TimelineEntry.OrderEntry -> {
                                val order = entry.order
                                val isExpanded = order.orderId in expandedOrderIds
                                PosOrderCard(
                                    order = order,
                                    isSelected = uiState.selectedOrder?.orderId == order.orderId,
                                    onClick = { viewModel.selectOrder(order) },
                                    expanded = isExpanded,
                                    onExpandToggle = {
                                        expandedOrderIds =
                                            if (isExpanded) {
                                                expandedOrderIds - order.orderId
                                            } else {
                                                expandedOrderIds + order.orderId
                                            }
                                    },
                                )
                            }
                            is TimelineEntry.TransferEntry -> {
                                val transfer = entry.transfer
                                val isExpanded = transfer.transferId in expandedTransferIds
                                PosTransferCard(
                                    transfer = transfer,
                                    isSelected = uiState.selectedTransfer?.transferId == transfer.transferId,
                                    onClick = { viewModel.selectTransfer(transfer) },
                                    expanded = isExpanded,
                                    onExpandToggle = {
                                        expandedTransferIds =
                                            if (isExpanded) {
                                                expandedTransferIds - transfer.transferId
                                            } else {
                                                expandedTransferIds + transfer.transferId
                                            }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        1 -> {
            if (uiState.bills.isEmpty()) {
                EmptyState(message = str(TableOrderHistoryKeys.noBills))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(uiState.bills, key = { "bill_${it.billId}" }) { bill ->
                        PosBillCard(
                            bill = bill,
                            onClick = {
                                viewModel.selectBill(bill)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Expanded layout: list on left (360dp) + detail panel on right.
 */
@Composable
private fun ExpandedContent(
    selectedTab: Int,
    uiState: TableOrderHistoryUiState,
    viewModel: TableOrderHistoryViewModel,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Left panel: list
        Box(modifier = Modifier.width(360.dp).fillMaxHeight()) {
            when (selectedTab) {
                0 -> {
                    if (!uiState.hasTimelineEntries) {
                        EmptyState(message = str(TableOrderHistoryKeys.noOrders))
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding =
                                PaddingValues(
                                    horizontal = 12.dp,
                                    vertical = 8.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                uiState.timeline,
                                key = { entry ->
                                    when (entry) {
                                        is TimelineEntry.OrderEntry -> "order_${entry.order.orderId}"
                                        is TimelineEntry.TransferEntry -> "transfer_${entry.transfer.transferId}"
                                    }
                                },
                            ) { entry ->
                                when (entry) {
                                    is TimelineEntry.OrderEntry -> {
                                        PosOrderCard(
                                            order = entry.order,
                                            isSelected = uiState.selectedOrder?.orderId == entry.order.orderId,
                                            onClick = { viewModel.selectOrder(entry.order) },
                                            expanded = false,
                                        )
                                    }
                                    is TimelineEntry.TransferEntry -> {
                                        PosTransferCard(
                                            transfer = entry.transfer,
                                            isSelected = uiState.selectedTransfer?.transferId == entry.transfer.transferId,
                                            onClick = { viewModel.selectTransfer(entry.transfer) },
                                            expanded = false,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    if (uiState.bills.isEmpty()) {
                        EmptyState(message = str(TableOrderHistoryKeys.noBills))
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding =
                                PaddingValues(
                                    horizontal = 12.dp,
                                    vertical = 8.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                uiState.bills,
                                key = { "bill_${it.billId}" },
                            ) { bill ->
                                PosBillCard(
                                    bill = bill,
                                    isSelected = uiState.selectedBill?.billId == bill.billId,
                                    onClick = { viewModel.selectBill(bill) },
                                )
                            }
                        }
                    }
                }
            }
        }

        VerticalDivider()

        // Right panel: detail
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when (selectedTab) {
                0 -> {
                    val selectedOrder = uiState.selectedOrder
                    val selectedTransfer = uiState.selectedTransfer
                    when {
                        selectedOrder != null -> {
                            PosOrderDetailPanel(
                                order = selectedOrder,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(12.dp),
                            )
                        }
                        selectedTransfer != null -> {
                            PosTransferDetailPanel(
                                transfer = selectedTransfer,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(12.dp),
                            )
                        }
                        else -> {
                            EmptyState(
                                message = str(TableOrderHistoryKeys.noOrders),
                            )
                        }
                    }
                }

                1 -> {
                    val selectedBill = uiState.selectedBill
                    if (selectedBill != null) {
                        PosBillDetailPanel(
                            bill = selectedBill,
                            tableNumber = uiState.tableNumber ?: uiState.tableId.toString(),
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                        )
                    } else {
                        EmptyState(
                            message = str(TableOrderHistoryKeys.noBills),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Empty state placeholder.
 */
@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
