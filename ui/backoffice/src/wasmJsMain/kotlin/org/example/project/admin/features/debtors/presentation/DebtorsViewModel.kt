package org.example.project.admin.features.debtors.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.DebtorApiClient
import org.example.project.core.data.remote.SalesInvoiceApiClient
import org.example.project.core.domain.dto.debtor.DebtorDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

/** How many of a debtor's invoices the detail pane shows. Recent history, not an archive. */
private const val INVOICE_PAGE_SIZE = 10

/** How long the typing has to stop before the search term is sent. */
private const val SEARCH_DEBOUNCE_MS = 300L

/**
 * Drives the Debitoren list and the detail pane beside it.
 *
 * The all/active filter is applied **server-side**: [setActiveOnly] re-requests rather than
 * narrowing the loaded list, because the server's list is paged and a client-side filter would
 * silently hide rows beyond the current page. The selected debtor's invoices are fetched the same
 * way, through the sales-invoice list's `debtorId` filter.
 */
class DebtorsViewModel(
    private val api: DebtorApiClient,
    private val salesInvoiceApi: SalesInvoiceApiClient,
) : ViewModel() {
    private val log = Logger.withTag("debtors")

    private var searchJob: Job? = null

    var state by mutableStateOf(DebtorsState())
        private set

    fun clearError() {
        state = state.copy(error = null)
    }

    fun load() {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi {
                api.list(
                    activeOnly = state.activeOnly,
                    search = state.search.trim().takeIf { it.isNotEmpty() },
                )
            }.onSuccess { response ->
                log.d { "debtors loaded: count=${response.items.size} total=${response.total}" }
                val selected = resolveSelection(response.items, state.selectedDebtorId)
                state =
                    state.copy(
                        isLoading = false,
                        items = response.items,
                        total = response.total,
                        selectedDebtorId = selected,
                    )
                if (selected != null) fetchInvoices(selected)
            }.onError(retry = { load() }) { uiError ->
                log.e { "debtors load failed: $uiError" }
                state = state.copy(isLoading = false, error = uiError)
            }
        }
    }

    fun setActiveOnly(value: Boolean) {
        if (value == state.activeOnly) return
        state = state.copy(activeOnly = value)
        load()
    }

    /**
     * Echoes the keystroke to the field at once, and re-requests once typing settles.
     *
     * Cancelling the pending job is the point, not the delay: the term is applied server-side, so an
     * un-debounced field fires one paged request per character, and their responses can land out of
     * order — leaving the last *response* on screen rather than the last request.
     */
    fun updateSearch(value: String) {
        state = state.copy(search = value)
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                load()
            }
    }

    /** Clears everything narrowing the list, in one request rather than one per filter. */
    fun resetFilters() {
        searchJob?.cancel()
        state = state.copy(search = "", activeOnly = false)
        load()
    }

    /**
     * Shows one debtor's record and invoices.
     *
     * The previous debtor's invoices are cleared **synchronously**, before the fetch is dispatched:
     * leaving them under the newly selected debtor's name would attribute one customer's billing
     * history to another.
     */
    fun selectDebtor(debtorId: Long) {
        if (debtorId == state.selectedDebtorId) return
        state = state.copy(selectedDebtorId = debtorId, invoices = emptyList(), isLoadingInvoices = true)
        viewModelScope.launch { fetchInvoices(debtorId) }
    }

    /**
     * Keeps the operator where they were looking across a reload, and selects **nothing** otherwise:
     * on arrival, and when the selected debtor is no longer in the list (which the active/all chip
     * does routinely).
     *
     * NEW-08: this used to fall back to the first row. On a phone and in the drill band below the
     * split width the list then showed that row as selected while no detail was open; Ausgangs-
     * rechnungen and Zuordnungen never auto-select, so neither does this list. Side by side the
     * detail pane shows its "pick a debtor" prompt instead.
     */
    private fun resolveSelection(
        debtors: List<DebtorDto.ResponseDto>,
        current: Long?,
    ): Long? = debtors.firstOrNull { it.id == current }?.id

    private suspend fun fetchInvoices(debtorId: Long) {
        state = state.copy(isLoadingInvoices = true)
        runCatchingApi { salesInvoiceApi.list(debtorId = debtorId, limit = INVOICE_PAGE_SIZE) }
            .onSuccess { response ->
                log.d { "debtor $debtorId invoices loaded: ${response.items.size}" }
                // Discard a response for a debtor the operator has already moved away from — two
                // quick clicks would otherwise let the slower answer win.
                if (state.selectedDebtorId != debtorId) return@onSuccess
                state = state.copy(isLoadingInvoices = false, invoices = response.items)
            }.onError(retry = { retryInvoices(debtorId) }) { uiError ->
                log.e { "debtor $debtorId invoices load failed: $uiError" }
                state = state.copy(isLoadingInvoices = false, error = uiError)
            }
    }

    /** The retry path for a failed invoice fetch — [selectDebtor] would no-op on the same id. */
    private fun retryInvoices(debtorId: Long) {
        viewModelScope.launch { fetchInvoices(debtorId) }
    }
}
