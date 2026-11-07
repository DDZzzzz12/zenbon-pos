package org.example.project.features.dashboard.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.core.data.domain.service.ClientOrderService
import org.example.project.core.data.provider.RTableDataProvider
import org.example.project.core.data.remote.BillApiClient
import org.example.project.core.data.remote.OrderApiClient
import org.example.project.core.data.remote.OrderItemApiClient
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.ItemTransferHistoryDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.features.dashboard.presentation.state.OrderItemForSelection
import org.example.project.features.dashboard.presentation.state.OrderSummary
import org.example.project.features.dashboard.presentation.state.TableInfo
import org.example.project.features.dashboard.presentation.state.TableOrderHistoryUiState
import org.example.project.features.dashboard.presentation.state.TableTab
import org.example.project.money.Decimal

/**
 * ViewModel for unified Table Management screen with three-tab interface.
 *
 * Manages the business logic for:
 * - Table details with item management (Details tab)
 * - Order history for the table session (Orders tab)
 * - Bills for the table session (Bills tab)
 *
 * Includes multi-select functionality, bulk operations (cancel/transfer),
 * and comprehensive state management for all three views.
 *
 * @property orderApiClient API client for order operations
 * @property billApiClient API client for bill operations
 * @property rTableDataProvider Data provider for table operations
 * @property orderService Service for order item operations (cancel, transfer)
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
class TableOrderHistoryViewModel(
    private val orderApiClient: OrderApiClient,
    private val billApiClient: BillApiClient,
    private val rTableDataProvider: RTableDataProvider,
    private val orderService: ClientOrderService,
    private val orderItemApiClient: OrderItemApiClient,
) : ViewModel() {
    private val logger = Logger.withTag("TableOrderHistoryViewModel")

    private val _uiState = MutableStateFlow(TableOrderHistoryUiState())
    val uiState: StateFlow<TableOrderHistoryUiState> = _uiState.asStateFlow()

    /**
     * Load order history and bills for a specific table session.
     *
     * Fetches all orders and bills associated with the given table session ID
     * and updates the UI state accordingly.
     *
     * @param tableId Table ID to fetch orders and bills for
     */
    fun loadOrderHistory(tableId: Long) {
        viewModelScope.launch {
            logger.i { "TableOrderHistoryViewModel.loadOrderHistory: loading for tableId=$tableId" }
            _uiState.update { it.copy(isLoading = true, error = null) }

            var table: org.example.project.core.data.models.RTable? = null
            var loadFailed = false
            runCatchingApi { rTableDataProvider.getRTableById(tableId) }
                .onSuccess { table = it }
                .onError { uiError ->
                    loadFailed = true
                    _uiState.update { it.copy(isLoading = false, error = uiError) }
                }
            if (loadFailed) return@launch
            val resolvedTable = table
            val sessionId = resolvedTable?.currentSessionId
            if (resolvedTable == null || sessionId == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error =
                            UiError.Unknown(
                                titleKey = CommonKeys.unknownErrorTitle,
                                messageKey = CommonKeys.unknownErrorMessage,
                            ),
                    )
                }
                return@launch
            }

            runCatchingApi { orderApiClient.getOrdersByTableSessionId(tableSessionId = sessionId) }
                .onSuccess { orders ->
                    logger.i { "TableOrderHistoryViewModel.loadOrderHistory: loaded ${orders.size} orders for table ${resolvedTable.tableNumber}" }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            orders = orders,
                            tableNumber = resolvedTable.tableNumber,
                            error = null,
                        )
                    }
                }.onError { uiError ->
                    logger.e { "TableOrderHistoryViewModel.loadOrderHistory: failed to load orders" }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = uiError,
                        )
                    }
                }

            loadBillsBySession(sessionId)
            loadTransferHistory(sessionId)
        }
    }

    /**
     * Load bills for a specific table session.
     *
     * @param tableSessionId Table session ID to fetch bills for
     */
    fun loadBills(tableId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingBills = true) }

            var sessionId: Long? = null
            runCatchingApi { rTableDataProvider.getRTableById(tableId) }
                .onSuccess { sessionId = it?.currentSessionId }
                .onError { _uiState.update { s -> s.copy(isLoadingBills = false) } }

            val resolvedSession = sessionId
            if (resolvedSession == null) {
                _uiState.update { it.copy(isLoadingBills = false) }
                return@launch
            }

            loadBillsBySession(resolvedSession)
        }
    }

    private fun loadBillsBySession(tableSessionId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingBills = true) }
            runCatchingApi {
                // Every Beleg of the session — Stornos and the cancelled originals included, each
                // with its relation chip — exactly as the back office lists them. A signed receipt
                // is a fiscal record; hiding the original left a Storno with nothing to reverse.
                billApiClient.getBillsByTableSessionId(
                    tableSessionId = tableSessionId,
                    includeDeleted = true,
                )
            }.onSuccess { bills ->
                _uiState.update {
                    it.copy(
                        isLoadingBills = false,
                        bills = bills,
                    )
                }
            }.onError {
                _uiState.update {
                    it.copy(isLoadingBills = false)
                }
            }
        }
    }

    private fun loadTransferHistory(tableSessionId: Long) {
        viewModelScope.launch {
            runCatchingApi { orderItemApiClient.getTransferHistory(tableSessionId) }
                .onSuccess { transfers ->
                    logger.i { "loadTransferHistory: loaded ${transfers.size} transfers" }
                    _uiState.update { it.copy(transfers = transfers) }
                }.onError { uiError ->
                    logger.e { "loadTransferHistory: failed to load transfers: $uiError" }
                }
        }
    }

    /**
     * Select an order for detailed view.
     *
     * @param order Order to select, or null to clear selection
     */
    fun selectOrder(order: OrderDto.OrderResponseDto?) {
        _uiState.update { it.copy(selectedOrder = order, selectedBill = null, selectedTransfer = null) }
    }

    /**
     * Select a bill for detailed view.
     *
     * @param bill Bill to select, or null to clear selection
     */
    fun selectBill(bill: BillDto.BillResponseDto?) {
        _uiState.update { it.copy(selectedBill = bill, selectedOrder = null, selectedTransfer = null) }
    }

    /**
     * Select a transfer for detailed view.
     *
     * @param transfer Transfer to select, or null to clear selection
     */
    fun selectTransfer(transfer: ItemTransferHistoryDto.ItemTransferHistoryResponseDto?) {
        _uiState.update {
            it.copy(
                selectedTransfer = transfer,
                selectedOrder = null,
                selectedBill = null,
            )
        }
    }

    /**
     * Clear any error messages.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Retry loading order history.
     *
     * Re-attempts to fetch order history using the current table ID.
     */
    fun retryLoad(tableSessionId: Long) {
        loadOrderHistory(tableSessionId)
    }

    // ==================== TAB MANAGEMENT ====================

    /**
     * Set the active tab and load tab-specific data if needed.
     *
     * @param tab Tab to activate (DETAILS, ORDERS, BILLS)
     */
    fun setActiveTab(tab: TableTab) {
        _uiState.update { it.copy(activeTab = tab) }

        // Clear selections when switching away from DETAILS tab
        if (tab != TableTab.DETAILS && _uiState.value.selectedItemIds.isNotEmpty()) {
            clearSelection()
        }
    }

    /**
     * Refresh the currently active tab.
     */
    fun refreshCurrentTab() {
        val tableId = _uiState.value.tableId
        when (_uiState.value.activeTab) {
            TableTab.DETAILS -> loadTableDetails(tableId)
            TableTab.ORDERS -> loadOrderHistory(tableId)
            TableTab.BILLS -> {
                viewModelScope.launch {
                    runCatchingApi { rTableDataProvider.getRTableById(tableId) }
                        .onSuccess { table ->
                            table?.currentSessionId?.let { sessionId -> loadBills(sessionId) }
                        }.onError { }
                }
            }
        }
    }

    // ==================== TABLE DETAILS METHODS ====================

    /**
     * Load table details and current order items for the Details tab.
     *
     * Fetches table information, current order items, and available tables
     * for potential transfer operations.
     *
     * @param tableId Unique identifier of the table
     */
    fun loadTableDetails(tableId: Long) {
        viewModelScope.launch {
            logger.i { "TableOrderHistoryViewModel.loadTableDetails: loading details for tableId=$tableId" }
            _uiState.update { it.copy(isLoading = true, error = null) }

            var tableQuery: org.example.project.core.data.models.RTable? = null
            runCatchingApi { rTableDataProvider.getRTableById(tableId) }
                .onSuccess { tableQuery = it }
                .onError { }

            val resolvedTable = tableQuery
            val sessionId = resolvedTable?.currentSessionId
            if (resolvedTable == null || sessionId == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error =
                            UiError.Unknown(
                                titleKey = CommonKeys.unknownErrorTitle,
                                messageKey = CommonKeys.unknownErrorMessage,
                            ),
                    )
                }
                return@launch
            }

            runCatchingApi { orderService.getOrderItemsForTable(sessionId) }
                .onSuccess { data ->
                    logger.i { "TableOrderHistoryViewModel.loadTableDetails: loaded ${data.size} order items" }
                    val orderItems =
                        data.map { item ->
                            val unitPrice = Decimal.parse(item.unitPrice)
                            OrderItemForSelection(
                                item = item,
                                maxQuantity = item.quantity,
                                selectedQuantity = 0,
                                unitPrice = unitPrice,
                                totalPrice = Decimal.ZERO,
                            )
                        }

                    var availableTables: List<org.example.project.core.data.models.RTable> = emptyList()
                    runCatchingApi { rTableDataProvider.getAllRTables() }
                        .onSuccess { availableTables = it }
                        .onError { }

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            tableInfo =
                                TableInfo(
                                    tableId = resolvedTable.id,
                                    tableNumber = resolvedTable.tableNumber,
                                    serverName = null,
                                    elapsedTimeSeconds = null,
                                    guestCount = null,
                                    status = resolvedTable.tableStatus,
                                ),
                            orderItems = orderItems,
                            summary =
                                OrderSummary(
                                    itemCount = 0,
                                    total = Decimal.ZERO,
                                ),
                            availableTables = availableTables,
                            tableNumber = resolvedTable.tableNumber,
                            tableId = tableId,
                            error = null,
                        )
                    }
                }.onError { uiError ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = uiError,
                        )
                    }
                }
        }
    }

    // ==================== SELECTION METHODS ====================

    /**
     * Increment selection state of an order item.
     *
     * @param item The order item to select
     */
    fun selectItem(item: OrderItemForSelection) {
        _uiState.update { state ->
            val updatedItems =
                state.orderItems.map {
                    if (it.orderItemIds == item.orderItemIds) {
                        val selectedQuantity = it.selectedQuantity
                        val quantity = if (selectedQuantity == item.maxQuantity) selectedQuantity else selectedQuantity + 1
                        val newTotalPrice = BillingMoneyMath.selectedLineTotal(it.unitPrice, quantity)
                        it.copy(
                            selectedQuantity = quantity,
                            totalPrice = newTotalPrice,
                        )
                    } else {
                        it
                    }
                }

            val selectedItems = updatedItems.filter { it.selectedQuantity > 0 }

            state.copy(
                orderItems = updatedItems,
                selectedItemIds = selectedItems.flatMap { it.orderItemIds.take(it.selectedQuantity) }.toSet(),
                isSelectionMode = selectedItems.isNotEmpty(),
            )
        }

        // Recalculate summary after selection change
        calculateOrderSummary()
    }

    /**
     * Decrement selection state of an order item.
     *
     * @param item The order item to deselect
     */
    fun deselectItem(item: OrderItemForSelection) {
        _uiState.update { state ->
            val updatedItems =
                state.orderItems.map {
                    if (it.orderItemIds == item.orderItemIds) {
                        val selectedQuantity = it.selectedQuantity
                        val quantity = if (selectedQuantity == 0) 0 else selectedQuantity - 1
                        val newTotalPrice = BillingMoneyMath.selectedLineTotal(it.unitPrice, quantity)
                        it.copy(
                            selectedQuantity = quantity,
                            totalPrice = newTotalPrice,
                        )
                    } else {
                        it
                    }
                }

            val selectedItems = updatedItems.filter { it.selectedQuantity > 0 }

            state.copy(
                orderItems = updatedItems,
                selectedItemIds = selectedItems.flatMap { it.orderItemIds.take(it.selectedQuantity) }.toSet(),
                isSelectionMode = selectedItems.isNotEmpty(),
            )
        }

        // Recalculate summary after selection change
        calculateOrderSummary()
    }

    /**
     * Clear all item selections.
     */
    fun clearSelection() {
        _uiState.update { state ->
            val updatedItems = state.orderItems.map { it.copy(selectedQuantity = 0) }

            state.copy(
                orderItems = updatedItems,
                selectedItemIds = emptySet(),
                isSelectionMode = false,
            )
        }

        // Recalculate summary after clearing selection
        calculateOrderSummary()
    }

    // ==================== DIALOG MANAGEMENT ====================

    /**
     * Show cancel items dialog.
     */
    fun showCancelDialog() {
        _uiState.update { it.copy(showCancelDialog = true) }
    }

    /**
     * Dismiss cancel items dialog.
     */
    fun dismissCancelDialog() {
        _uiState.update { it.copy(showCancelDialog = false) }
    }

    /**
     * Show transfer items dialog.
     */
    fun showTransferDialog() {
        _uiState.update { it.copy(showTransferDialog = true) }
    }

    /**
     * Dismiss transfer items dialog.
     */
    fun dismissTransferDialog() {
        _uiState.update { it.copy(showTransferDialog = false) }
    }

    // ==================== BULK OPERATIONS ====================

    /**
     * Cancel selected order items with optional reason.
     *
     * This operation requires manager authorization (handled by backend).
     *
     * @param reason Optional reason for cancellation
     * @param sourceTableId ID of the table containing the items
     */
    fun cancelSelectedItems(
        reason: String,
        sourceTableId: Long,
    ) {
        viewModelScope.launch {
            try {
                val currentState = _uiState.value
                val selectedItemIds = currentState.selectedItemIds.toList()
                logger.i { "TableOrderHistoryViewModel.cancelSelectedItems: cancelling ${selectedItemIds.size} items from tableId=$sourceTableId" }

                if (selectedItemIds.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            error =
                                UiError.Unknown(
                                    titleKey = CommonKeys.unknownErrorTitle,
                                    messageKey = CommonKeys.unknownErrorMessage,
                                ),
                        )
                    }
                    return@launch
                }

                _uiState.update { it.copy(isLoading = true) }

                runCatchingApi {
                    orderService.cancelOrderItems(
                        itemIds = selectedItemIds,
                        reason = reason.ifBlank { null },
                        sourceTableId = sourceTableId,
                    )
                }.onSuccess {
                    logger.i { "TableOrderHistoryViewModel.cancelSelectedItems: cancelled ${selectedItemIds.size} items successfully" }
                    loadTableDetails(sourceTableId)
                    _uiState.update {
                        it.copy(
                            selectedItemIds = emptySet(),
                            showCancelDialog = false,
                            isLoading = false,
                            error = null,
                        )
                    }
                }.onError { uiError ->
                    logger.e { "TableOrderHistoryViewModel.cancelSelectedItems: failed: $uiError" }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showCancelDialog = false,
                            error = uiError,
                        )
                    }
                }
            } catch (e: Exception) {
                logger.e(e) { "TableOrderHistoryViewModel.cancelSelectedItems: unexpected error" }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        showCancelDialog = false,
                        error =
                            UiError.Unknown(
                                titleKey = CommonKeys.unknownErrorTitle,
                                messageKey = CommonKeys.unknownErrorMessage,
                                cause = e,
                            ),
                    )
                }
            }
        }
    }

    /**
     * Transfer selected order items to another table.
     *
     * This operation requires manager authorization (handled by backend).
     *
     * @param sourceTableId ID of the source table
     * @param targetTableId ID of the target table to transfer items to
     */
    fun transferSelectedItems(
        sourceTableId: Long,
        targetTableId: Long,
        reason: String,
    ) {
        viewModelScope.launch {
            try {
                logger.i { "TableOrderHistoryViewModel.transferSelectedItems: transferring items from tableId=$sourceTableId to tableId=$targetTableId" }
                val currentState = _uiState.value
                val selectedItemIds = currentState.selectedItemIds.toList()

                if (selectedItemIds.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            error =
                                UiError.Unknown(
                                    titleKey = CommonKeys.unknownErrorTitle,
                                    messageKey = CommonKeys.unknownErrorMessage,
                                ),
                        )
                    }
                    return@launch
                }

                if (currentState.tableInfo?.tableId == targetTableId) {
                    _uiState.update {
                        it.copy(
                            error =
                                UiError.Unknown(
                                    titleKey = CommonKeys.unknownErrorTitle,
                                    messageKey = CommonKeys.unknownErrorMessage,
                                ),
                        )
                    }
                    return@launch
                }

                _uiState.update { it.copy(isLoading = true) }

                runCatchingApi {
                    orderService.transferOrderItems(
                        itemIds = selectedItemIds,
                        targetTableId = targetTableId,
                        sourceTableId = sourceTableId,
                        reason = reason,
                    )
                }.onSuccess {
                    logger.i { "TableOrderHistoryViewModel.transferSelectedItems: transferred ${selectedItemIds.size} items successfully" }
                    loadTableDetails(sourceTableId)
                    _uiState.update {
                        it.copy(
                            selectedItemIds = emptySet(),
                            showTransferDialog = false,
                            isLoading = false,
                            error = null,
                        )
                    }
                }.onError { uiError ->
                    logger.e { "TableOrderHistoryViewModel.transferSelectedItems: failed: $uiError" }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showTransferDialog = false,
                            error = uiError,
                        )
                    }
                }
            } catch (e: Exception) {
                logger.e(e) { "TableOrderHistoryViewModel.transferSelectedItems: unexpected error" }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        showTransferDialog = false,
                        error =
                            UiError.Unknown(
                                titleKey = CommonKeys.unknownErrorTitle,
                                messageKey = CommonKeys.unknownErrorMessage,
                                cause = e,
                            ),
                    )
                }
            }
        }
    }

    /**
     * Clear success message.
     */
    fun clearSuccessMessage() {
        _uiState.update { it.copy(successMessage = null) }
    }

    // ==================== HELPER METHODS ====================

    /**
     * Calculate order summary from selected order items and update UI state.
     *
     * Accesses the current UI state and calculates summary only for items
     * where selectedQuantity > 0. Uses the selectedQuantity to determine
     * the actual quantity for each item in the calculation.
     *
     * Directly updates the UI state with the calculated summary.
     */
    private fun calculateOrderSummary() {
        _uiState.update { currentState ->
            val items = currentState.orderItems

            val summary =
                if (items.isEmpty()) {
                    OrderSummary(
                        itemCount = 0,
                        total = Decimal.ZERO,
                    )
                } else {
                    // Calculate based on selected quantities
                    val itemCount = items.sumOf { it.selectedQuantity }
                    val total = items.fold(Decimal.ZERO) { acc, item -> acc + item.totalPrice }

                    OrderSummary(
                        itemCount = itemCount,
                        total = total,
                    )
                }

            currentState.copy(summary = summary)
        }
    }
}
