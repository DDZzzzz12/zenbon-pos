package org.example.project.admin.features.contracts.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.ContractApiClient
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

/** How long the typing has to stop before the search term is sent. */
private const val SEARCH_DEBOUNCE_MS = 300L

/**
 * Drives the Verträge list.
 *
 * The *inaktive einblenden* toggle and the search box are both applied **server-side**:
 * [setIncludeInactive] and [updateSearch] re-request rather than narrowing the loaded rows, because
 * the server owns that predicate and two places deciding what a list contains is how the two come to
 * disagree.
 */
class ContractsViewModel(
    private val api: ContractApiClient,
) : ViewModel() {
    private val log = Logger.withTag("contracts")

    private var searchJob: Job? = null

    var state by mutableStateOf(ContractsState())
        private set

    fun clearError() {
        state = state.copy(error = null)
    }

    fun load() {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi {
                api.list(
                    includeInactive = state.includeInactive,
                    search = state.search.trim().takeIf { it.isNotEmpty() },
                )
            }.onSuccess { response ->
                log.d { "contracts loaded: count=${response.items.size} total=${response.total}" }
                state =
                    state.copy(
                        isLoading = false,
                        items = response.items,
                        total = response.total,
                    )
                loadAlerts()
            }.onError(retry = { load() }) { uiError ->
                log.e { "contracts load failed: $uiError" }
                state = state.copy(isLoading = false, error = uiError)
            }
        }
    }

    /**
     * A second request, after the contracts rather than beside them: a failure here must surface as
     * an error **without** blanking the rows already on screen. A contract list with no summary line
     * is still worth reading; an empty table because the alerts call failed is not.
     */
    private suspend fun loadAlerts() {
        runCatchingApi { api.alerts() }
            .onSuccess { response ->
                log.d { "contract alerts loaded: ${response.items.size}" }
                state = state.copy(alerts = response.items)
            }.onError { uiError ->
                log.e { "contract alerts failed: $uiError" }
                state = state.copy(error = uiError)
            }
    }

    /** Returns without dispatching when the flag is already applied — a no-op toggle is not a request. */
    fun setIncludeInactive(value: Boolean) {
        if (value == state.includeInactive) return
        state = state.copy(includeInactive = value)
        load()
    }

    /**
     * Echoes the keystroke to the field at once, and re-requests once typing settles.
     *
     * Debounced even though this list is not paged: the request also re-derives every contract's
     * alerts, so one call per keystroke is real work on the server for a term the operator has not
     * finished typing.
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
        state = state.copy(search = "", includeInactive = false)
        load()
    }
}
