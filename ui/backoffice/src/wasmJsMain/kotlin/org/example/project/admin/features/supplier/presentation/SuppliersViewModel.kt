package org.example.project.admin.features.supplier.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.SupplierApiClient
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

/** How long the typing has to stop before the search term is sent. */
private const val SEARCH_DEBOUNCE_MS = 300L

/**
 * Drives the supplier list. The chip filter, the search term and the ordering are all applied
 * **server-side**: each of [setProvisionalOnly], [updateSearch] and [sortBy] re-requests rather than
 * narrowing or reordering the loaded list, because the server's list is paged and doing either on
 * the client would silently act on one page while appearing to act on the list.
 */
class SuppliersViewModel(
    private val api: SupplierApiClient,
) : ViewModel() {
    private val log = Logger.withTag("suppliers")

    private var searchJob: Job? = null
    private var loadJob: Job? = null

    var state by mutableStateOf(SuppliersState())
        private set

    fun clearError() {
        state = state.copy(error = null)
    }

    fun load() {
        state = state.copy(isLoading = true, error = null)
        // Only the latest request may land: a panel apply that changes the toggle and the sort loads twice.
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                runCatchingApi {
                    api.list(
                        provisionalOnly = state.provisionalOnly,
                        search = state.search.trim().takeIf { it.isNotEmpty() },
                        sort = state.sort.wireValue,
                        ascending = state.sortAscending,
                    )
                }.onSuccess { response ->
                    log.d { "suppliers loaded: count=${response.items.size} total=${response.total}" }
                    state =
                        state.copy(
                            isLoading = false,
                            items = response.items,
                            total = response.total,
                        )
                }.onError(retry = { load() }) { uiError ->
                    log.e { "suppliers load failed: $uiError" }
                    state = state.copy(isLoading = false, error = uiError)
                }
            }
    }

    fun setProvisionalOnly(value: Boolean) {
        if (value == state.provisionalOnly) return
        state = state.copy(provisionalOnly = value)
        load()
    }

    /**
     * Echoes the keystroke to the field at once, and re-requests once typing settles.
     *
     * Cancelling the pending job is the point, not the delay: the term is applied server-side, so an
     * un-debounced field fires one paged request per character, and their responses can land out of
     * order — leaving the *last response* on screen rather than the last request, showing results
     * for a prefix of what the operator is looking at.
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

    /** A second click on the sorted column reverses it; a different column starts ascending. */
    fun sortBy(column: SupplierSortColumn) {
        state =
            if (column == state.sort) {
                state.copy(sortAscending = !state.sortAscending)
            } else {
                state.copy(sort = column, sortAscending = true)
            }
        load()
    }

    /** Applies a sort picked in the phone filter panel, in one request. */
    fun setSort(
        column: SupplierSortColumn,
        ascending: Boolean,
    ) {
        if (column == state.sort && ascending == state.sortAscending) return
        state = state.copy(sort = column, sortAscending = ascending)
        load()
    }

    /** Clears everything narrowing the list, in one request rather than one per filter. */
    fun resetFilters() {
        searchJob?.cancel()
        state = state.copy(search = "", provisionalOnly = false)
        load()
    }
}
