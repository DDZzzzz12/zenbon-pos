package org.example.project.admin.features.reports.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.data.remote.MonthlyCloseApiClient
import org.example.project.core.domain.enums.setting.DatevExportCadence
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.features.reports.presentation.ReportsGranularity
import org.example.project.features.reports.presentation.calendarBounds
import org.example.project.features.reports.presentation.isCurrentPeriod
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Loads `GET /api/v1/monthly-close` for the period the Berichte screen is showing.
 *
 * A second ViewModel rather than a sixth `async` inside [AnalyticsViewModel]: the endpoint is
 * MANAGER-only (`RoutePermissions`: `"/api/v1/monthly-close" to Get` → `Role(MANAGER)`) while
 * `AdminNavItem.ANALYTICS` is gated `{ true }`, so sharing a `coroutineScope` would let a single
 * 403 fail the whole screen for every non-manager.
 */
@OptIn(ExperimentalTime::class)
class CloseReadinessViewModel(
    private val monthlyCloseApiClient: MonthlyCloseApiClient,
) : ViewModel() {
    private val log = Logger.withTag("close-readiness")

    var state by mutableStateOf(CloseReadinessState())
        private set

    fun clearError() {
        state = state.copy(error = null)
    }

    fun load(
        granularity: ReportsGranularity,
        anchor: LocalDate,
    ) {
        state = state.copy(isLoading = true, error = null)
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val (start, end) = halfOpenWindow(granularity, anchor, now)
        val cadence = cadenceFor(granularity)
        viewModelScope.launch {
            runCatchingApi {
                monthlyCloseApiClient.getMonthlyClose(
                    periodStart = start,
                    periodEnd = end,
                    comparePreviousPeriod = true,
                    cadence = cadence,
                )
            }.onSuccess { response ->
                log.d { "monthly close loaded for $granularity @ $anchor" }
                state = state.copy(isLoading = false, data = response)
            }.onError(retry = { load(granularity, anchor) }) { uiError ->
                log.e { "monthly close load failed for $granularity @ $anchor" }
                state = state.copy(isLoading = false, error = uiError)
            }
        }
    }

    /**
     * `[start, end)` for the period, matching `MonthlyCloseService`'s documented half-open interval.
     * Deliberately not `ReportsGranularity.windowFor`, whose end is the inclusive `23:59:59` the
     * statistics endpoint wants — reusing it would drop the final second of every closed period.
     */
    private fun halfOpenWindow(
        granularity: ReportsGranularity,
        anchor: LocalDate,
        now: LocalDateTime,
    ): Pair<String, String> {
        val (calendarStart, calendarEnd) = granularity.calendarBounds(anchor)
        val start = LocalDateTime(calendarStart, LocalTime(0, 0))
        val end =
            if (granularity.isCurrentPeriod(anchor, now.date)) {
                now
            } else {
                LocalDateTime(calendarEnd.plus(DatePeriod(days = 1)), LocalTime(0, 0))
            }
        return start.toString() to end.toString()
    }

    /**
     * The cadence the server uses to judge DATEV export coverage for the window. Mirrors the till's
     * rule: a week-long window is judged against weekly exports, everything else against monthly.
     */
    private fun cadenceFor(granularity: ReportsGranularity): DatevExportCadence =
        when (granularity) {
            ReportsGranularity.WEEK -> DatevExportCadence.WEEKLY
            else -> DatevExportCadence.MONTHLY
        }
}
