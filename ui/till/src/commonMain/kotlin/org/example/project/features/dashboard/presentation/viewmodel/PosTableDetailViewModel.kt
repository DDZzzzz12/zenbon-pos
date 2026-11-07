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
import org.example.project.core.data.remote.ApprovalOutcome
import org.example.project.core.data.remote.EcrConfigurationApiClient
import org.example.project.core.data.remote.OrderApiClient
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TableDetailKeys
import org.example.project.core.presentation.strings.keys.TemporarySessionKeys
import org.example.project.core.utils.LanguageManager
import org.example.project.features.dashboard.presentation.state.OrderItemForSelection
import org.example.project.features.dashboard.presentation.state.PosTableDetailUiState
import org.example.project.features.dashboard.presentation.state.TableInfo
import org.example.project.features.dashboard.presentation.state.TableOperationsUiState
import org.example.project.features.dashboard.presentation.state.toSelectionRow
import org.example.project.features.dashboard.presentation.state.withSurvivingSelection
import org.example.project.money.Decimal

/**
 * ViewModel for POS table detail screen operations.
 *
 * Manages order item selection and bulk operations (cancel, transfer)
 * for the redesigned POS table detail screen.
 *
 * @property rTableDataProvider Data provider for table reads
 * @property orderService Service for order item operations (get, cancel, transfer)
 * @property shopConfigurationApiClient Reads the shop's print-settings, which decide whether
 *   „Referenzdruck“ is offered
 * @property shopId The paired shop's id, or null before pairing
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
class PosTableDetailViewModel(
    private val rTableDataProvider: RTableDataProvider,
    private val orderService: ClientOrderService,
    private val orderApiClient: OrderApiClient,
    private val languageManager: LanguageManager,
    private val ecrConfigurationApiClient: EcrConfigurationApiClient,
    private val shopId: () -> Long?,
) : ViewModel() {
    private val logger = Logger.withTag("PosTableDetailViewModel")

    /** PosSuccessWatcher shows success text verbatim, so it is resolved here in the operator's language. */
    private fun localized(key: StringKey): String = getString(key, languageManager.currentLanguage.value)

    private val _uiState = MutableStateFlow(PosTableDetailUiState())
    val uiState: StateFlow<PosTableDetailUiState> = _uiState.asStateFlow()

    // ==================== DATA LOADING ====================

    /**
     * Load table details and current order items.
     *
     * Fetches table information, current order items, and available tables
     * for potential transfer operations.
     *
     * @param tableId Unique identifier of the table
     */
    fun loadTableDetails(tableId: Long) {
        viewModelScope.launch {
            try {
                logger.i { "PosTableDetailViewModel.loadTableDetails: loading details for tableId=$tableId" }
                _uiState.value = PosTableDetailUiState(isLoading = true, referencePrintOffered = _uiState.value.referencePrintOffered)
                launch { loadReferencePrintAvailability() }

                // Load table information
                val tableQuery = runCatchingApi { rTableDataProvider.getRTableById(tableId) }.getOrNull()
                val sessionId = tableQuery?.currentSessionId
                if (tableQuery == null || sessionId == null) {
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
                        logger.i { "PosTableDetailViewModel.loadTableDetails: loaded ${data.size} order items" }
                        val orderItems = data.map { it.toSelectionRow() }

                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                tableInfo =
                                    TableInfo(
                                        tableId = tableQuery.id,
                                        tableNumber = tableQuery.tableNumber,
                                        serverName = null,
                                        elapsedTimeSeconds = null,
                                        guestCount = null,
                                        status = tableQuery.tableStatus,
                                    ),
                                orderItems = orderItems,
                                tableNumber = tableQuery.tableNumber,
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
            } catch (e: Exception) {
                logger.e(e) { "PosTableDetailViewModel.loadTableDetails: unexpected error" }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = UiError.Unknown(cause = e),
                    )
                }
            }
        }
    }

    /**
     * Re-reads the rows after this table changed on the server, keeping the surviving selection.
     *
     * Driven by the table's status push. When a manager approved a cashier's Storno nothing
     * reloaded this panel: the cancelled units stayed listed at their old quantities, and selected,
     * until the table was reopened (E2E-2026-09-20 round 2, R2-3). Unlike [loadTableDetails] this
     * does not reset the screen, because the same push fires when another waiter merely adds a
     * drink, and a selection still awaiting a manager must stay visible.
     *
     * A failed background refresh is logged and leaves the rows as they are; the next push or an
     * explicit reload corrects it.
     *
     * @param tableId The table the push was about; ignored unless it is the one on screen
     * @param sessionId The table's session as the push reports it — read from the push rather than
     *   the local cache, which is written only after the push is emitted. Null: the table has no
     *   session left, so it has no rows.
     */
    fun refreshAfterTableChange(
        tableId: Long,
        sessionId: Long?,
    ) {
        if (tableId != _uiState.value.tableId) return
        viewModelScope.launch {
            val rows =
                if (sessionId == null) {
                    emptyList()
                } else {
                    runCatchingApi { orderService.getOrderItemsForTable(sessionId) }
                        .getOrNull()
                        ?.map { it.toSelectionRow() }
                }
            if (rows == null) {
                logger.w { "PosTableDetailViewModel.refreshAfterTableChange: reload failed for tableId=$tableId" }
                return@launch
            }
            _uiState.update { state ->
                val reconciled = rows.withSurvivingSelection(state.selectedItemIds)
                val selectedItems = reconciled.filter { it.selectedQuantity > 0 }
                state.copy(
                    orderItems = reconciled,
                    selectedItemIds = selectedItems.flatMap { it.orderItemIds.take(it.selectedQuantity) }.toSet(),
                    isSelectionMode = selectedItems.isNotEmpty(),
                )
            }
        }
    }

    /**
     * Search for tables matching the given query string at the database level.
     *
     * Delegates to [RTableDataProvider.searchRTables] which performs an efficient
     * database query with smart prioritization:
     * 1. Exact match (highest priority)
     * 2. Table number starts with query
     * 3. Table number contains query (lowest priority)
     *
     * Results are limited to 5 entries and stored in [TableOperationsUiState.tableSearchResults].
     *
     * @param query The search query to match against table numbers
     */
    fun searchTablesByNumber(query: String) {
        if (query.isBlank()) {
            _uiState.update { it.copy(availableTables = emptyList()) }
            return
        }

        viewModelScope.launch {
            runCatchingApi { rTableDataProvider.searchRTables(query.trim()) }
                .onSuccess { results -> _uiState.update { it.copy(availableTables = results) } }
                .onError { _uiState.update { it.copy(availableTables = emptyList()) } }
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
                        val newTotalPrice = it.unitPrice * Decimal.of(quantity)
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
                        val newTotalPrice = it.unitPrice * Decimal.of(quantity)
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
    }

    /**
     * Reset selection of a specific item back to 0.
     */
    fun deselectAll(item: OrderItemForSelection) {
        _uiState.update { state ->
            val updatedItems =
                state.orderItems.map {
                    if (it.orderItemIds == item.orderItemIds) {
                        it.copy(selectedQuantity = 0, totalPrice = Decimal.ZERO)
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
    }

    /**
     * Toggle multi-select mode on or off.
     */
    fun toggleSelectionMode() {
        val currentMode = _uiState.value.isSelectionMode
        if (currentMode) {
            clearSelection()
        } else {
            _uiState.update { it.copy(isSelectionMode = true) }
        }
    }

    // ==================== DIALOG MANAGEMENT ====================

    /**
     * Show cancel items dialog.
     */
    fun showCancelDialog() {
        if (refuseStornoWithoutManager()) return
        _uiState.update { it.copy(showCancelDialog = true) }
    }

    /**
     * Whether the Storno is offered — set by the screen from `StationCorrectionActions.offersStorno`
     * as the session's capabilities change (a manager signs in on the station, or the sign-in ends).
     * Withdrawing it also closes an open Storno dialog.
     */
    fun setStornoOffered(offered: Boolean) {
        _uiState.update { it.copy(stornoOffered = offered, showCancelDialog = it.showCancelDialog && offered) }
    }

    /**
     * The client half of the station-Storno guard (owner decision 2026-10-03, emu21 NOTE-v): with the
     * Storno withdrawn, nothing is sent — no approval request is ever raised from a station. Returns
     * true when it refused. The server refuses the same call with `STATION_STORNO_REQUIRES_MANAGER`.
     */
    private fun refuseStornoWithoutManager(): Boolean {
        if (_uiState.value.stornoOffered) return false
        _uiState.update {
            it.copy(
                showCancelDialog = false,
                error =
                    UiError.Unknown(
                        titleKey = TemporarySessionKeys.stationStornoNeedsManagerTitle,
                        messageKey = TemporarySessionKeys.stationStornoNeedsManagerMessage,
                        code = PosErrorCode.STATION_STORNO_REQUIRES_MANAGER,
                    ),
            )
        }
        return true
    }

    /**
     * Dismiss cancel items dialog.
     */
    fun hideCancelDialog() {
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
    fun hideTransferDialog() {
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
     * @param onSuccess Callback invoked after successful cancellation
     */
    fun cancelSelectedItems(
        reason: String,
        sourceTableId: Long,
        onSuccess: () -> Unit,
    ) {
        if (refuseStornoWithoutManager()) return
        viewModelScope.launch {
            try {
                val currentState = _uiState.value
                val selectedItemIds = currentState.selectedItemIds.toList()
                logger.i { "PosTableDetailViewModel.cancelSelectedItems: cancelling ${selectedItemIds.size} items from tableId=$sourceTableId" }

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
                }.onSuccess { outcome ->
                    // 202: nothing has happened yet — a manager has to decide. Reporting success
                    // here left the cashier unable to tell "waiting" from "the tap did nothing"
                    // (E2E-2026-09-20 #6). The selection is kept so the request stays visible as
                    // the thing that is pending.
                    if (outcome == ApprovalOutcome.AWAITING_APPROVAL) {
                        logger.i { "PosTableDetailViewModel.cancelSelectedItems: awaiting manager approval" }
                        _uiState.update {
                            it.copy(
                                showCancelDialog = false,
                                isLoading = false,
                                error = null,
                                successMessage = localized(TableDetailKeys.awaitingManagerApproval),
                            )
                        }
                        return@onSuccess
                    }
                    logger.i { "PosTableDetailViewModel.cancelSelectedItems: cancelled ${selectedItemIds.size} items successfully" }
                    loadTableDetails(sourceTableId)
                    _uiState.update {
                        it.copy(
                            selectedItemIds = emptySet(),
                            showCancelDialog = false,
                            isLoading = false,
                            error = null,
                        )
                    }
                    onSuccess()
                }.onError { uiError ->
                    logger.e { "PosTableDetailViewModel.cancelSelectedItems: failed: $uiError" }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showCancelDialog = false,
                            error = uiError,
                        )
                    }
                }
            } catch (e: Exception) {
                logger.e(e) { "PosTableDetailViewModel.cancelSelectedItems: unexpected error" }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        showCancelDialog = false,
                        error = UiError.Unknown(cause = e),
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
     * @param onSuccess Callback invoked after successful transfer
     */
    fun transferSelectedItems(
        sourceTableId: Long,
        targetTableId: Long,
        reason: String,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                logger.i { "PosTableDetailViewModel.transferSelectedItems: transferring items from tableId=$sourceTableId to tableId=$targetTableId" }
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
                }.onSuccess { outcome ->
                    // See cancelSelectedItems: a 202 is a request, not a result.
                    if (outcome == ApprovalOutcome.AWAITING_APPROVAL) {
                        logger.i { "PosTableDetailViewModel.transferSelectedItems: awaiting manager approval" }
                        _uiState.update {
                            it.copy(
                                showTransferDialog = false,
                                isLoading = false,
                                error = null,
                                successMessage = localized(TableDetailKeys.awaitingManagerApproval),
                            )
                        }
                        return@onSuccess
                    }
                    logger.i { "PosTableDetailViewModel.transferSelectedItems: transferred ${selectedItemIds.size} items successfully" }
                    loadTableDetails(sourceTableId)
                    _uiState.update {
                        it.copy(
                            selectedItemIds = emptySet(),
                            showTransferDialog = false,
                            isLoading = false,
                            error = null,
                        )
                    }
                    onSuccess()
                }.onError { uiError ->
                    logger.e { "PosTableDetailViewModel.transferSelectedItems: failed: $uiError" }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showTransferDialog = false,
                            error = uiError,
                        )
                    }
                }
            } catch (e: Exception) {
                logger.e(e) { "PosTableDetailViewModel.transferSelectedItems: unexpected error" }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        showTransferDialog = false,
                        error = UiError.Unknown(cause = e),
                    )
                }
            }
        }
    }

    // ==================== STATE MANAGEMENT ====================

    /**
     * Clear any error state.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Clear success message.
     */
    fun clearSuccessMessage() {
        _uiState.update { it.copy(successMessage = null) }
    }

    /**
     * Read the shop's print-settings: with printing disabled „Referenzdruck“ is not offered, since
     * the server refuses it (409 `PRINTING_DISABLED`) — SET-04. Read on every panel open, so a toggle
     * made in Einstellungen → Drucker during the shift applies without a restart. An unreadable
     * setting leaves the action offered; the server's refusal still explains it.
     */
    private suspend fun loadReferencePrintAvailability() {
        val shop = shopId() ?: return
        runCatchingApi { ecrConfigurationApiClient.getPrintSettings(shop) }
            .onSuccess { settings -> _uiState.update { it.copy(referencePrintOffered = settings.enabled) } }
            .onError { uiError -> logger.w { "PosTableDetailViewModel: print-settings not read: $uiError" } }
    }

    fun printReferencePrint() {
        val state = _uiState.value
        val tableId = state.tableId
        if (tableId <= 0L || !state.referencePrintOffered) return

        val selectedItemIds =
            state.orderItems
                .filter { it.isSelected }
                .flatMap { it.orderItemIds.take(it.selectedQuantity) }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val request =
                OrderItemDto.ReferencePrintRequestDto(
                    tableId = tableId,
                    orderItemIds = selectedItemIds,
                )
            runCatchingApi { orderApiClient.printReferencePrint(request) }
                .onSuccess {
                    _uiState.update {
                        it.copy(isLoading = false, successMessage = localized(TableDetailKeys.referencePrintSent))
                    }
                }.onError { uiError ->
                    _uiState.update {
                        it.copy(isLoading = false, error = uiError)
                    }
                }
        }
    }
}
