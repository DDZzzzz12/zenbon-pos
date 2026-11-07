package org.example.project.ui.screens.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.auth.LocalPermissionManager
import org.example.project.core.auth.LocalSettingManager
import org.example.project.core.auth.LocalTemporarySessionManager
import org.example.project.core.auth.trackPrivilegedOperation
import org.example.project.core.data.models.RTable
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.DashboardKeys
import org.example.project.core.presentation.strings.keys.TableDetailKeys
import org.example.project.core.presentation.strings.keys.TableOrderKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalNotificationViewModel
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.features.dashboard.presentation.viewmodel.PosTableDetailViewModel
import org.example.project.features.notifications.presentation.viewmodel.NotificationUiEvent
import org.example.project.money.Decimal
import org.example.project.ui.auth.holds
import org.example.project.ui.auth.rememberIsStation
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDangerIconButton
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosMarqueeText
import org.example.project.ui.components.PosNavIconButton
import org.example.project.ui.components.PosOrderItemCard
import org.example.project.ui.components.PosStatusChip
import org.example.project.ui.components.PosSuccessWatcher
import org.example.project.ui.theme.PosSpacing
import org.example.project.ui.theme.PosTheme
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosTableDetailScreen(
    modifier: Modifier = Modifier,
    table: RTable,
    onBack: () -> Unit,
    onNewOrder: () -> Unit,
    onCreateBill: () -> Unit,
    onRequestBilling: () -> Unit = {},
    onTransfer: () -> Unit,
    onHistory: () -> Unit,
    onAssignCustomer: () -> Unit,
    onAddVoucher: () -> Unit = {},
    onDelete: () -> Unit,
    detailViewModel: PosTableDetailViewModel = koinViewModel(),
) {
    val permissionManager = LocalPermissionManager.current
    val settingManager = LocalSettingManager.current
    val sessionManager = LocalTemporarySessionManager.current
    val currentEmployeeNumber by permissionManager.currentEmployeeNumber.collectAsState()
    val voucherEnabled by settingManager.voucherEnabled.collectAsState()
    val voucherSources by settingManager.voucherSources.collectAsState()
    val voucherCreationEnabled = voucherSources.find { it.type == VoucherSourceType.INTERNAL }?.enabled == true
    val allowCrossEmployeeBilling by settingManager.allowCrossEmployeeBilling.collectAsState()
    val ownsBill = TableBillingActions.ownsBill(currentEmployeeNumber, table.currentEmployeeNumber, allowCrossEmployeeBilling)
    // Whether this session bills at all comes from the station marker, NOT the employee number: the
    // station account has an employee number too, so the shop's cross-employee setting flipped the
    // station's billing actions (OS-b). A person's own login clears the marker, so it bills on any
    // device and the server decides by attribution. Read once (rememberIsStation) — the marker
    // cannot change while this panel lives.
    val isStation = rememberIsStation()

    LaunchedEffect(table.id) {
        detailViewModel.loadTableDetails(table.id)
    }

    // The table changed on the server — an approved Storno, a transfer, another device's order.
    // Without this the panel kept showing cancelled units, still selected (E2E-2026-09-20 R2-3).
    val notificationViewModel = LocalNotificationViewModel.current
    LaunchedEffect(table.id) {
        notificationViewModel.notificationEvents.collect { event ->
            if (event is NotificationUiEvent.TableStatusChanged && event.tableId == table.id) {
                detailViewModel.refreshAfterTableChange(event.tableId, event.sessionId)
            }
        }
    }
    val detailState by detailViewModel.uiState.collectAsState()

    // NOTE-v (owner decision 2026-10-03): a station offers the Storno only while a manager is
    // signed in on it — the sign-in lends ORDER_ITEM_CANCEL, and `holds` follows it in and out.
    // Never "Freigabe anfordern" from a station; the server refuses that request too.
    val stornoOffered = StationCorrectionActions.offersStorno(isStation, holds(Capability.ORDER_ITEM_CANCEL))
    LaunchedEffect(stornoOffered) { detailViewModel.setStornoOffered(stornoOffered) }

    // Who authorizes a Storno right now — the manager of an active temporary session (NOTE-ee).
    val temporarySession = sessionManager?.uiState?.collectAsState()?.value
    val activeManagerName = temporarySession?.takeIf { it.hasActiveSession }?.managerName

    PosErrorWatcher(
        error = detailState.error,
        title = str(CommonKeys.error),
        onClear = { detailViewModel.clearError() },
    )

    PosSuccessWatcher(
        message = detailState.successMessage,
        title = str(DashboardKeys.successTitle),
        onClear = { detailViewModel.clearSuccessMessage() },
    )

    val tableStatus =
        try {
            TableStatus.valueOf(table.tableStatus)
        } catch (_: Exception) {
            TableStatus.FREE
        }

    val statusColor =
        when {
            !table.isActive -> MaterialTheme.colorScheme.onSurfaceVariant
            tableStatus == TableStatus.FREE -> PosTheme.colors.posAvailable
            tableStatus == TableStatus.OCCUPIED -> PosTheme.colors.posOccupied
            tableStatus == TableStatus.RESERVED -> PosTheme.colors.posReserved
            else -> PosTheme.colors.posAvailable
        }

    val statusText =
        when (tableStatus) {
            TableStatus.FREE -> str(DashboardKeys.statusFree)
            TableStatus.OCCUPIED -> str(DashboardKeys.statusOccupied)
            TableStatus.RESERVED -> str(DashboardKeys.statusReserved)
        }

    val hasSelection = detailState.selectedItemIds.isNotEmpty()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    PosMarqueeText(
                        text = "${str(DashboardKeys.tableLabel)} ${table.tableNumber}",
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    PosNavIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        onClick = onBack,
                    )
                },
                actions = {
                    PosStatusChip(
                        text = statusText,
                        backgroundColor = statusColor.copy(alpha = 0.12f),
                        textColor = statusColor,
                    )
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                        .padding(bottom = if (hasSelection) 60.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PosTableInfoSection(table = table)

                Text(
                    text = str(DashboardKeys.actionsLabel),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                PosTableActionGrid(
                    onNewOrder = onNewOrder,
                    onCreateBill = onCreateBill,
                    onRequestBilling = onRequestBilling,
                    onTransfer = onTransfer,
                    onHistory = onHistory,
                    onAssignCustomer = onAssignCustomer,
                    onAddVoucher = onAddVoucher,
                    onDelete = onDelete,
                    ownsBill = ownsBill,
                    isStation = isStation,
                    isVoucherEnabled = voucherEnabled && voucherCreationEnabled,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Current Order section
                Text(
                    text = str(DashboardKeys.currentOrderLabel),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                if (detailState.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                } else if (detailState.orderItems.isEmpty()) {
                    Text(
                        text = str(TableOrderKeys.emptyOrderTitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    detailState.orderItems.forEach { item ->
                        val isCancelled = item.item.itemStatus.uppercase() == "CANCELLED"
                        PosOrderItemCard(
                            quantity = item.maxQuantity,
                            productName = "${item.item.product.productNumber}-${item.item.product.productName}",
                            price = item.item.finalPrice,
                            extras = item.item.extras?.joinToString(", ") { it.name },
                            sideNotes = item.item.sideNotes,
                            isCancelled = isCancelled,
                            isSelected = item.selectedQuantity > 0,
                            onClick =
                                if (!isCancelled) {
                                    {
                                        if (item.selectedQuantity >= item.maxQuantity) {
                                            detailViewModel.deselectAll(item)
                                        } else {
                                            detailViewModel.selectItem(item)
                                        }
                                    }
                                } else {
                                    null
                                },
                            trailingContent =
                                if (item.selectedQuantity > 0) {
                                    {
                                        Surface(
                                            color = MaterialTheme.colorScheme.tertiaryContainer,
                                            shape = MaterialTheme.shapes.small,
                                        ) {
                                            Text(
                                                text =
                                                    if (item.maxQuantity > 1) {
                                                        "${item.selectedQuantity} / ${item.maxQuantity}"
                                                    } else {
                                                        str(TableDetailKeys.selected, item.selectedQuantity)
                                                    },
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(horizontal = PosSpacing.sm, vertical = 2.dp),
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
                        )
                    }

                    val activeItems =
                        detailState.orderItems
                            .filter { it.item.itemStatus.uppercase() != "CANCELLED" }
                    val totalAmount =
                        if (hasSelection) {
                            activeItems.fold(Decimal.ZERO) { acc, item -> acc + item.unitPrice * Decimal.of(item.selectedQuantity) }
                        } else {
                            activeItems.fold(Decimal.ZERO) { acc, item -> acc + item.unitPrice * Decimal.of(item.maxQuantity) }
                        }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text =
                                str(
                                    if (hasSelection) {
                                        DashboardKeys.selectedTotalLabel
                                    } else {
                                        DashboardKeys.orderTotalLabel
                                    },
                                ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = totalAmount.toFormattedCurrency(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    // Hidden while printing is disabled for the shop (SET-04); the server refuses it too.
                    if (detailState.referencePrintOffered) {
                        Spacer(modifier = Modifier.height(12.dp))

                        PosButton(
                            text = str(TableDetailKeys.referencePrint),
                            onClick = { detailViewModel.printReferencePrint() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled =
                                detailState.orderItems.any {
                                    it.item.itemStatus.uppercase() != "CANCELLED"
                                },
                            isLoading = detailState.isLoading,
                        )
                    }
                }
            }

            // Bulk action toolbar — slides in from bottom when items are selected
            AnimatedVisibility(
                visible = hasSelection,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Clear selection
                        PosIconButton(
                            onClick = { detailViewModel.clearSelection() },
                            icon = Icons.Default.Cancel,
                            contentDescription = str(TableDetailKeys.clearSelection),
                        )
                        Spacer(modifier = Modifier.weight(1f))

                        PosIconButton(
                            icon = Icons.Default.SwapHoriz,
                            contentDescription = str(TableDetailKeys.transferItems),
                            onClick = { detailViewModel.showTransferDialog() },
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )

                        if (detailState.stornoOffered) {
                            PosDangerIconButton(
                                icon = Icons.Default.Delete,
                                contentDescription = str(TableDetailKeys.cancelItems),
                                onClick = { detailViewModel.showCancelDialog() },
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        // Cancel dialog
        if (detailState.showCancelDialog && detailState.selectedItemIds.isNotEmpty()) {
            PosCancelItemsDialog(
                itemCount = detailState.selectedItemIds.size,
                hint = CancelAuthorizationHint.of(activeManagerName, holds(Capability.ORDER_ITEM_CANCEL)),
                authorizingManager = activeManagerName,
                onConfirm = { reason ->
                    trackPrivilegedOperation(sessionManager)
                    detailViewModel.cancelSelectedItems(reason, table.id) {
                        onBack()
                    }
                },
                onDismiss = { detailViewModel.hideCancelDialog() },
            )
        }

        // Transfer dialog
        if (detailState.showTransferDialog && detailState.selectedItemIds.isNotEmpty()) {
            PosTransferItemsDialog(
                itemCount = detailState.selectedItemIds.size,
                currentTableId = table.id,
                availableTables = detailState.availableTables,
                onConfirm = { targetTableId, reason ->
                    trackPrivilegedOperation(sessionManager)
                    detailViewModel.transferSelectedItems(table.id, targetTableId, reason) {
                        onBack()
                    }
                },
                onDismiss = { detailViewModel.hideTransferDialog() },
                loadTables = { detailViewModel.searchTablesByNumber(it) },
            )
        }
    }
}
