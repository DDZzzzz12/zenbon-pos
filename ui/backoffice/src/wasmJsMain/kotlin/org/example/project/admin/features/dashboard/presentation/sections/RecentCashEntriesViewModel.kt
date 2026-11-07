package org.example.project.admin.features.dashboard.presentation.sections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.CashBookApiClient
import org.example.project.core.data.remote.KassenbuchApiClient
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

private const val LATEST_ENTRIES_COUNT = 5

/** The last five cash-book entries — the only cash-ledger signal the landing screen carries. */
class RecentCashEntriesViewModel(
    private val kassenbuchApiClient: KassenbuchApiClient,
    private val cashBookApiClient: CashBookApiClient,
) : ViewModel() {
    private val log = Logger.withTag("dashboard-cash-entries")

    var state by mutableStateOf(RecentCashEntriesState())
        private set

    fun refresh() {
        state = state.copy(isLoading = true, failed = false)
        viewModelScope.launch {
            runCatchingApi {
                kassenbuchApiClient.getRecentEntries(LATEST_ENTRIES_COUNT).sortedBy { it.headerId }
            }.onSuccess { entries ->
                state = RecentCashEntriesState(isLoading = false, failed = false, entries = entries, bookNames = loadBookNames())
            }.onError { uiError ->
                log.w { "recent cash entries load failed: $uiError" }
                state = RecentCashEntriesState(isLoading = false, failed = true)
            }
        }
    }

    /** A book list that cannot be read only costs the balance lines their names; the entries still show. */
    private suspend fun loadBookNames(): Map<Long, String> =
        runCatchingApi { cashBookApiClient.listBooks() }
            .getOrNull()
            ?.associate { it.id to it.name }
            .orEmpty()
}
