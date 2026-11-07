package org.example.project.admin.features.zBon.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.data.remote.ZBonApiClient
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.features.reports.presentation.ReportsGranularity
import org.example.project.features.reports.presentation.windowFor
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Drives the Z-Bon history list screen. For the selected [ReportsGranularity] anchored at a date it
 * computes the reporting window and fetches the Z-Bon summaries for it. The history endpoint parses
 * date-only bounds (`LocalDate.parse(YYYY-MM-DD)`), so the time component of the window is stripped
 * before the request.
 */
class ZBonListViewModel(
    private val zBonApiClient: ZBonApiClient,
) : ViewModel() {
    private val log = Logger.withTag("zBonList")

    @OptIn(ExperimentalTime::class)
    private fun today(): LocalDate =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date

    var state by mutableStateOf(ZBonListState(anchor = today()))
        private set

    fun clearError() {
        state = state.copy(error = null)
    }

    @OptIn(ExperimentalTime::class)
    fun load(
        granularity: ReportsGranularity,
        anchor: LocalDate,
    ) {
        state = state.copy(isLoading = true, error = null, granularity = granularity, anchor = anchor)
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val (startIso, endIso) = granularity.windowFor(anchor, now)
        val start = startIso.substringBefore('T')
        val end = endIso.substringBefore('T')
        viewModelScope.launch {
            runCatchingApi { zBonApiClient.getZBonHistory(start, end) }
                .onSuccess { zBons ->
                    log.d { "zBon history loaded for $granularity @ $anchor: ${zBons.size} reports" }
                    state = state.copy(isLoading = false, zBons = zBons)
                }.onError(retry = { load(granularity, anchor) }) { uiError ->
                    log.e { "zBon history load failed for $granularity @ $anchor" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }
}
