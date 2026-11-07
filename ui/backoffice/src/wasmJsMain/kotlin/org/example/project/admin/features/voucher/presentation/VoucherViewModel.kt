package org.example.project.admin.features.voucher.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.data.remote.VoucherApiClient
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.features.reports.presentation.ReportsGranularity
import org.example.project.features.reports.presentation.windowFor
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val PAGE_SIZE = 50
private const val SEARCH_DEBOUNCE_MS = 300L

/**
 * Drives the Gutscheine back-office screen.
 *
 * Both date-range endpoints load on every period or employee change regardless of the visible tab,
 * because the two period KPIs are derived from their results. `active` is global (outstanding
 * liability, not a flow) and is never invalidated by the period; `statistics` is lifetime and loads
 * once.
 */
@OptIn(ExperimentalTime::class)
class VoucherViewModel(
    private val voucherApi: VoucherApiClient,
) : ViewModel() {
    private val log = Logger.withTag("voucher")
    private var searchJob: Job? = null
    private var loadJob: Job? = null

    private fun today(): LocalDate =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date

    var state by mutableStateOf(VoucherUiState(anchor = today()))
        private set

    fun clearError() {
        state = state.copy(error = null)
    }

    /**
     * Fetches both date-range lists for the period, plus the one-shot statistics and currency.
     *
     * Cancels any still-running call from a previous invocation before launching a new one: two
     * quick period-stepper clicks (or an employee-filter change right after one) would otherwise
     * leave two in-flight request pairs, and whichever happened to land last — not the one matching
     * the [granularity]/[anchor] the state already shows — would win the write, silently mislabeling
     * the table and both period KPIs. Cancelling the stale job means its `onSuccess`/`onError` never
     * runs at all, so it can never overwrite the newer state.
     */
    fun load(
        granularity: ReportsGranularity,
        anchor: LocalDate,
        employeeId: String? = state.employeeId,
    ) {
        state =
            state.copy(
                isLoading = true,
                error = null,
                granularity = granularity,
                anchor = anchor,
                employeeId = employeeId,
            )
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val (startIso, endIso) = granularity.windowFor(anchor, now)

        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                // coroutineScope so a failing child is rethrown here and caught by runCatchingApi,
                // instead of escaping to viewModelScope and killing the screen silently.
                runCatchingApi {
                    coroutineScope {
                        val issuedDeferred = async { voucherApi.getVouchersCreatedInRange(startIso, endIso, employeeId) }
                        val redeemedDeferred = async { voucherApi.getVouchersRedeemedInRange(startIso, endIso, employeeId) }
                        issuedDeferred.await() to redeemedDeferred.await()
                    }
                }.onSuccess { (issued, redeemed) ->
                    log.d { "period $granularity @ $anchor: ${issued.size} issued, ${redeemed.size} redeemed" }
                    val currency = issued.firstOrNull()?.currency ?: redeemed.firstOrNull()?.currency
                    state =
                        state.copy(
                            issued = issued,
                            redeemed = redeemed,
                            isLoading = false,
                            currencyCode = currency ?: state.currencyCode,
                        )
                }.onError { uiError ->
                    log.e { "period load failed: $uiError" }
                    state = state.copy(isLoading = false, error = uiError)
                }
            }
        loadStatisticsOnce()
    }

    /**
     * Loads the lifetime statistics once per screen session. Doubles as the most reliable source
     * for [VoucherUiState.currencyCode]: unlike the period/active/search lists it is a single
     * object rather than a list that can come back empty, so it always carries a `currency` value
     * whenever the request succeeds — every voucher endpoint resolves the same shop-config
     * currency server-side, so any of them is an equally valid source, but this one is guaranteed
     * to be non-empty.
     */
    private fun loadStatisticsOnce() {
        if (state.statistics != null) return
        viewModelScope.launch {
            runCatchingApi { voucherApi.getVoucherStatistics() }
                .onSuccess { state = state.copy(statistics = it, currencyCode = it.currency) }
                .onError { uiError ->
                    // Non-fatal: the lifetime KPIs render a placeholder rather than blocking the screen.
                    log.w { "statistics unavailable: $uiError" }
                }
        }
    }

    fun selectTab(tab: VoucherTab) {
        state = state.copy(tab = tab)
        if (tab == VoucherTab.ACTIVE && !state.activeLoaded) loadActive(reset = true)
    }

    fun selectEmployee(employeeId: String?) {
        if (employeeId == state.employeeId) return
        load(state.granularity, state.anchor, employeeId)
    }

    private fun loadActive(reset: Boolean) {
        val offset = if (reset) 0 else state.active.size
        state = if (reset) state.copy(isLoading = true) else state.copy(isLoadingMore = true)
        viewModelScope.launch {
            runCatchingApi { voucherApi.getActiveVouchers(PAGE_SIZE, offset) }
                .onSuccess { page ->
                    val merged = if (reset) page else state.active + page
                    state =
                        state.copy(
                            active = merged,
                            activeLoaded = true,
                            activeHasMore = page.size == PAGE_SIZE,
                            isLoading = false,
                            isLoadingMore = false,
                            currencyCode = page.firstOrNull()?.currency ?: state.currencyCode,
                        )
                }.onError { uiError ->
                    log.e { "active load failed: $uiError" }
                    state = state.copy(isLoading = false, isLoadingMore = false, error = uiError)
                }
        }
    }

    fun loadMoreActive() {
        if (!state.activeHasMore || state.isLoadingMore) return
        loadActive(reset = false)
    }

    /** Debounced; a blank term never reaches the server, which rejects it with 400. */
    fun onSearchTermChange(term: String) {
        state = state.copy(searchTerm = term)
        searchJob?.cancel()
        if (term.isBlank()) {
            // isLoading/isLoadingMore must be cleared here: runCatchingApi rethrows
            // CancellationException, so cancelling an in-flight search runs neither onSuccess nor
            // onError, and PosLoadingOverlay is a non-dismissible Dialog — a stuck flag hard-locks
            // the screen until reload.
            state =
                state.copy(
                    searchCommitted = false,
                    searchResults = emptyList(),
                    isLoading = false,
                    isLoadingMore = false,
                )
            return
        }
        searchJob =
            viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                runSearch(term, reset = true)
            }
    }

    fun clearSearch() {
        searchJob?.cancel()
        // Same cancellation reasoning as onSearchTermChange: clear the loading flags by hand.
        state =
            state.copy(
                searchTerm = "",
                searchCommitted = false,
                searchResults = emptyList(),
                isLoading = false,
                isLoadingMore = false,
            )
    }

    fun loadMoreSearch() {
        if (!state.searchHasMore || state.isLoadingMore || !state.isSearchActive) return
        viewModelScope.launch { runSearch(state.searchTerm, reset = false) }
    }

    private suspend fun runSearch(
        term: String,
        reset: Boolean,
    ) {
        val offset = if (reset) 0 else state.searchResults.size
        state = if (reset) state.copy(isLoading = true) else state.copy(isLoadingMore = true)
        runCatchingApi { voucherApi.searchVouchersByNumber(term, PAGE_SIZE, offset) }
            .onSuccess { page ->
                state =
                    state.copy(
                        searchResults = if (reset) page else state.searchResults + page,
                        searchCommitted = true,
                        searchHasMore = page.size == PAGE_SIZE,
                        isLoading = false,
                        isLoadingMore = false,
                    )
            }.onError { uiError ->
                log.e { "search failed: $uiError" }
                state = state.copy(isLoading = false, isLoadingMore = false, error = uiError)
            }
    }

    /** Opens the drawer immediately from the row data, then fills the history behind its own spinner. */
    fun select(voucher: VoucherDto.VoucherResponseDto) {
        state = state.copy(selected = voucher, history = null, isHistoryLoading = true)
        viewModelScope.launch {
            runCatchingApi { voucherApi.getRedemptionHistory(voucher.voucherNumber) }
                .onSuccess { state = state.copy(history = it, isHistoryLoading = false) }
                .onError { uiError ->
                    log.e { "history load failed: $uiError" }
                    state = state.copy(isHistoryLoading = false, error = uiError)
                }
        }
    }

    /**
     * Opens the drawer from a redemption row. `VoucherRedemptionSummaryDto` carries no status and no
     * voucher id, so the voucher is resolved by exact number through search — the endpoint matches a
     * pattern, hence the equality filter.
     */
    fun selectByNumber(voucherNumber: String) {
        viewModelScope.launch {
            runCatchingApi { voucherApi.searchVouchersByNumber(voucherNumber, PAGE_SIZE, 0) }
                .onSuccess { matches ->
                    val exact = matches.firstOrNull { it.voucherNumber == voucherNumber }
                    if (exact == null) {
                        log.w { "no exact match for $voucherNumber" }
                    } else {
                        select(exact)
                    }
                }.onError { uiError ->
                    log.e { "voucher resolve failed: $uiError" }
                    state = state.copy(error = uiError)
                }
        }
    }

    fun closeDrawer() {
        state = state.copy(selected = null, history = null, isHistoryLoading = false)
    }
}
