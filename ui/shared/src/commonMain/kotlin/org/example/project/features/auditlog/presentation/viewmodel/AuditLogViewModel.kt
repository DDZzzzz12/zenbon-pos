/**
 * ViewModel for the Audit Log screen.
 *
 * Manages paginated loading of entry logs and transaction audit logs
 * with filter support and tab switching.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.auditlog.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.AuditLogApiClient
import org.example.project.core.domain.dto.ApiResponseDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.features.auditlog.presentation.state.AuditLogTab
import org.example.project.features.auditlog.presentation.state.AuditLogUiState
import org.example.project.features.auditlog.presentation.state.EntryLogFilterState
import org.example.project.features.auditlog.presentation.state.TransactionAuditFilterState

/**
 * ViewModel for the Audit Log screen.
 *
 * Manages two-tab paginated audit log display with:
 * - Entry Log tab: Lightweight CRUD operation tracking
 * - Transaction Audit tab: Detailed financial/operational audit trail
 * - Per-tab filter state and pagination
 *
 * @param auditLogApiClient API client for fetching audit logs
 */
@OptIn(FlowPreview::class)
class AuditLogViewModel(
    private val auditLogApiClient: AuditLogApiClient,
) : ViewModel() {
    private val logger = Logger.withTag("AuditLogViewModel")

    private val _uiState = MutableStateFlow(AuditLogUiState())
    val uiState: StateFlow<AuditLogUiState> = _uiState.asStateFlow()

    /** Current page per tab (0-indexed) */
    private val currentPages =
        mutableMapOf(
            AuditLogTab.ENTRY_LOG to 0,
            AuditLogTab.TRANSACTION_AUDIT to 0,
        )

    private val entryLogFilterFlow = MutableStateFlow(EntryLogFilterState())

    private val transactionAuditFilterFlow = MutableStateFlow(TransactionAuditFilterState())

    init {
        entryLogFilterFlow
            .drop(1) // skip initial value; explicit loads handle first paint
            .debounce(DEBOUNCE_MS)
            .distinctUntilChanged()
            .onEach { loadEntryLogs(0) }
            .launchIn(viewModelScope)

        transactionAuditFilterFlow
            .drop(1)
            .debounce(DEBOUNCE_MS)
            .distinctUntilChanged()
            .onEach { loadTransactionAuditLogs(0) }
            .launchIn(viewModelScope)

        loadEntryLogs(0) // default tab, immediate
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
    }

    /**
     * Switch to a different tab.
     */
    fun onTabChanged(tab: AuditLogTab) {
        _uiState.update { it.copy(selectedTab = tab) }
        // Load data for the newly selected tab if not yet loaded
        when (tab) {
            AuditLogTab.ENTRY_LOG -> {
                if (_uiState.value.entryLogs.isEmpty() && !_uiState.value.isEntryLogLoading) {
                    loadEntryLogs(currentPages[AuditLogTab.ENTRY_LOG] ?: 0)
                }
            }
            AuditLogTab.TRANSACTION_AUDIT -> {
                if (_uiState.value.transactionAuditLogs.isEmpty() && !_uiState.value.isTransactionAuditLoading) {
                    loadTransactionAuditLogs(currentPages[AuditLogTab.TRANSACTION_AUDIT] ?: 0)
                }
            }
        }
    }

    /**
     * Toggle filter panel visibility.
     */
    fun toggleFilterPanel() {
        _uiState.update { it.copy(isFilterPanelVisible = !it.isFilterPanelVisible) }
    }

    /** Update entry log filter; UI updates immediately, network reload is debounced. */
    fun updateEntryLogFilter(filter: EntryLogFilterState) {
        _uiState.update { it.copy(entryLogFilter = filter) }
        currentPages[AuditLogTab.ENTRY_LOG] = 0
        entryLogFilterFlow.value = filter
    }

    /** Clear entry log filters. */
    fun clearEntryLogFilter() = updateEntryLogFilter(EntryLogFilterState())

    /** Update transaction audit filter; UI updates immediately, network reload is debounced. */
    fun updateTransactionAuditFilter(filter: TransactionAuditFilterState) {
        _uiState.update { it.copy(transactionAuditFilter = filter) }
        currentPages[AuditLogTab.TRANSACTION_AUDIT] = 0
        transactionAuditFilterFlow.value = filter
    }

    /** Clear transaction audit filters. */
    fun clearTransactionAuditFilter() = updateTransactionAuditFilter(TransactionAuditFilterState())

    /** Change entry log page size and reload immediately (not debounced). */
    fun setEntryLogPageSize(size: Int) {
        _uiState.update { it.copy(entryLogPageSize = size) }
        currentPages[AuditLogTab.ENTRY_LOG] = 0
        loadEntryLogs(0)
    }

    /** Change transaction audit page size and reload immediately (not debounced). */
    fun setTransactionAuditPageSize(size: Int) {
        _uiState.update { it.copy(transactionAuditPageSize = size) }
        currentPages[AuditLogTab.TRANSACTION_AUDIT] = 0
        loadTransactionAuditLogs(0)
    }

    /**
     * Navigate to the next page for the currently selected tab.
     */
    fun loadNextPage() {
        val tab = _uiState.value.selectedTab
        val pagination = getCurrentPagination() ?: return
        if (!pagination.hasNext) return

        val nextPage = (currentPages[tab] ?: 0) + 1
        currentPages[tab] = nextPage
        when (tab) {
            AuditLogTab.ENTRY_LOG -> loadEntryLogs(nextPage)
            AuditLogTab.TRANSACTION_AUDIT -> loadTransactionAuditLogs(nextPage)
        }
    }

    /**
     * Navigate to the previous page for the currently selected tab.
     */
    fun loadPreviousPage() {
        val tab = _uiState.value.selectedTab
        val pagination = getCurrentPagination() ?: return
        if (!pagination.hasPrevious) return

        val prevPage = (currentPages[tab] ?: 0) - 1
        currentPages[tab] = prevPage
        when (tab) {
            AuditLogTab.ENTRY_LOG -> loadEntryLogs(prevPage)
            AuditLogTab.TRANSACTION_AUDIT -> loadTransactionAuditLogs(prevPage)
        }
    }

    /**
     * Get current pagination info for the selected tab.
     */
    fun getCurrentPagination(): ApiResponseDto.PaginationInfo? =
        when (_uiState.value.selectedTab) {
            AuditLogTab.ENTRY_LOG -> _uiState.value.entryLogPagination
            AuditLogTab.TRANSACTION_AUDIT -> _uiState.value.transactionAuditPagination
        }

    /**
     * Clear the error state.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Load entry logs for the specified page.
     */
    private fun loadEntryLogs(page: Int) {
        viewModelScope.launch {
            logger.i { "AuditLogViewModel.loadEntryLogs: loading page=$page" }
            _uiState.update { it.copy(isEntryLogLoading = true, error = null) }

            val filter = _uiState.value.entryLogFilter
            runCatchingApi {
                auditLogApiClient.getPaginatedEntryLogs(
                    type = filter.type,
                    operation = filter.operation,
                    entityName = filter.entityName,
                    entityId = filter.entityId,
                    employeeNumber = filter.employeeNumber,
                    fromDate = filter.fromDate,
                    toDate = filter.toDate,
                    search = filter.search,
                    page = page,
                    size = _uiState.value.entryLogPageSize,
                )
            }.onSuccess { resp ->
                logger.i { "AuditLogViewModel.loadEntryLogs: loaded ${resp.logs.size} entry logs" }
                _uiState.update {
                    it.copy(
                        entryLogs = resp.logs,
                        entryLogPagination = resp.pagination,
                        isEntryLogLoading = false,
                    )
                }
            }.onError { uiError ->
                logger.e { "AuditLogViewModel.loadEntryLogs: failed to load entry logs: $uiError" }
                _uiState.update {
                    it.copy(
                        error = uiError,
                        isEntryLogLoading = false,
                    )
                }
            }
        }
    }

    /**
     * Load transaction audit logs for the specified page.
     */
    private fun loadTransactionAuditLogs(page: Int) {
        viewModelScope.launch {
            logger.i { "AuditLogViewModel.loadTransactionAuditLogs: loading page=$page" }
            _uiState.update { it.copy(isTransactionAuditLoading = true, error = null) }

            val filter = _uiState.value.transactionAuditFilter
            runCatchingApi {
                auditLogApiClient.getPaginatedTransactionAuditLogs(
                    employeeId = filter.employeeId,
                    employeeNumber = filter.employeeNumber,
                    operation = filter.operation,
                    entityType = filter.entityType,
                    entityId = filter.entityId,
                    entityName = filter.entityName,
                    fromDate = filter.fromDate,
                    toDate = filter.toDate,
                    search = filter.search,
                    amountMin = filter.amountMin,
                    amountMax = filter.amountMax,
                    authorizationType = filter.authorizationType,
                    page = page,
                    size = _uiState.value.transactionAuditPageSize,
                )
            }.onSuccess { resp ->
                logger.i { "AuditLogViewModel.loadTransactionAuditLogs: loaded ${resp.logs.size} audit logs" }
                _uiState.update {
                    it.copy(
                        transactionAuditLogs = resp.logs,
                        transactionAuditPagination = resp.pagination,
                        isTransactionAuditLoading = false,
                    )
                }
            }.onError { uiError ->
                logger.e { "AuditLogViewModel.loadTransactionAuditLogs: failed to load audit logs: $uiError" }
                _uiState.update {
                    it.copy(
                        error = uiError,
                        isTransactionAuditLoading = false,
                    )
                }
            }
        }
    }
}
