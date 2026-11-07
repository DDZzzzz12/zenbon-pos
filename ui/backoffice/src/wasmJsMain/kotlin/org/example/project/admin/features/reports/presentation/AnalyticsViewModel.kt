package org.example.project.admin.features.reports.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.data.remote.StatisticsApiClient
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.features.reports.presentation.ReportsGranularity
import org.example.project.features.reports.presentation.windowFor
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Drives the Berichte (reports) screen. For the selected [ReportsGranularity] anchored at a date, it
 * computes the reporting window (ending at now for the current period, at the calendar end for a past
 * one) and fetches the sales overview — asking for a like-for-like baseline via `compare=previous`
 * when the compare toggle is on — and the trends time-series in parallel. The granularity's `.name`
 * is the server's `granularity` query parameter.
 */
class AnalyticsViewModel(
    private val statisticsApiClient: StatisticsApiClient,
) : ViewModel() {
    private val log = Logger.withTag("reports")

    @OptIn(ExperimentalTime::class)
    private fun today(): LocalDate =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date

    var state by mutableStateOf(ReportsState(anchor = today()))
        private set

    fun clearError() {
        state = state.copy(error = null)
    }

    /** Flips the compare toggle and reloads; no-op when the value is unchanged. */
    fun setCompareEnabled(enabled: Boolean) {
        if (enabled == state.compareEnabled) return
        state = state.copy(compareEnabled = enabled)
        load(state.granularity, state.anchor, compareEnabled = enabled)
    }

    /** Changes the employee filter and reloads; no-op when the selection is unchanged. */
    fun selectEmployee(employeeId: String?) {
        if (employeeId == state.employeeId) return
        load(state.granularity, state.anchor, employeeId = employeeId)
    }

    @OptIn(ExperimentalTime::class)
    fun load(
        granularity: ReportsGranularity,
        anchor: LocalDate,
        compareEnabled: Boolean = state.compareEnabled,
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
        // Compare needs the granularity so the server can pick the right baseline window; sending
        // neither keeps the response free of comparison data (KPI chips and growth columns hidden).
        val compare = if (compareEnabled) "previous" else null
        val granularityParam = if (compareEnabled) granularity.name else null
        viewModelScope.launch {
            // coroutineScope so a failing parallel child is rethrown here and caught by
            // runCatchingApi, instead of escaping to viewModelScope.
            runCatchingApi {
                coroutineScope {
                    val overviewDeferred =
                        async {
                            statisticsApiClient.getSalesOverview(
                                startDate = startIso,
                                endDate = endIso,
                                employeeId = employeeId,
                                compare = compare,
                                granularity = granularityParam,
                            )
                        }
                    val trendsDeferred =
                        async {
                            statisticsApiClient.getSalesTrends(
                                startDate = startIso,
                                endDate = endIso,
                                employeeId = employeeId,
                            )
                        }
                    val financialDeferred =
                        async {
                            statisticsApiClient.getFinancialOverview(
                                startDate = startIso,
                                endDate = endIso,
                                employeeId = employeeId,
                            )
                        }
                    val catalogDeferred =
                        async {
                            statisticsApiClient.getCatalog(
                                startDate = startIso,
                                endDate = endIso,
                                employeeId = employeeId,
                                compare = compare,
                                granularity = granularityParam,
                            )
                        }
                    val promotionsDeferred =
                        async {
                            statisticsApiClient.getPromotions(
                                startDate = startIso,
                                endDate = endIso,
                                employeeId = employeeId,
                                compare = compare,
                                granularity = granularityParam,
                            )
                        }
                    Loaded(
                        overviewDeferred.await(),
                        trendsDeferred.await(),
                        financialDeferred.await(),
                        catalogDeferred.await(),
                        promotionsDeferred.await(),
                    )
                }
            }.onSuccess { loaded ->
                log.d { "reports loaded for $granularity @ $anchor (compare=$compareEnabled, employeeId=$employeeId)" }
                state =
                    state.copy(
                        isLoading = false,
                        overview = loaded.overview,
                        trends = loaded.trends,
                        financial = loaded.financial,
                        catalog = loaded.catalog,
                        promotions = loaded.promotions,
                    )
            }.onError(retry = { load(granularity, anchor) }) { uiError ->
                log.e { "reports load failed for $granularity @ $anchor" }
                state = state.copy(isLoading = false, error = uiError)
            }
        }
    }
}

private data class Loaded(
    val overview: StatisticsDto.SalesOverviewResponseDto,
    val trends: StatisticsDto.SalesTrendsResponseDto,
    val financial: StatisticsDto.FinancialOverviewResponseDto,
    val catalog: StatisticsDto.CatalogBreakdownResponseDto,
    val promotions: StatisticsDto.PromotionBreakdownResponseDto,
)
