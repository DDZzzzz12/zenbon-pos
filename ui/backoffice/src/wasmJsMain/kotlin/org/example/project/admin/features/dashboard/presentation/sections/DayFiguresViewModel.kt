package org.example.project.admin.features.dashboard.presentation.sections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.data.remote.StatisticsApiClient
import org.example.project.core.data.remote.ZBonApiClient
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.utils.getZBonStartTime
import kotlin.time.Clock

/**
 * The four figures of the business day: revenue, bill count, open amount, tips.
 *
 * The window is the fiscal day — last Z-Bon end to now — not the calendar day, so the figures agree
 * with the Tagesbericht the button next to them opens (its default range is the same
 * `getZBonStartTime`). The card is therefore titled "Seit letztem Z-Bon", not "Heute": after the
 * evening closing a day with sales correctly reads zero, and a "today" label made that look like a
 * lost day (QA admin18 NOTE-17).
 */
class DayFiguresViewModel(
    private val statisticsApiClient: StatisticsApiClient,
    private val zBonApiClient: ZBonApiClient,
) : ViewModel() {
    private val log = Logger.withTag("dashboard-day-figures")

    var state by mutableStateOf(DayFiguresState())
        private set

    fun refresh() {
        state = state.copy(isLoading = true, failed = false)
        viewModelScope.launch {
            runCatchingApi {
                val start = getZBonStartTime(zBonApiClient)
                val end =
                    Clock.System
                        .now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                        .toString()
                statisticsApiClient.getSalesOverview(startDate = start, endDate = end)
            }.onSuccess { overview ->
                state = DayFiguresState(isLoading = false, failed = false, overview = overview)
            }.onError { uiError ->
                log.w { "day figures load failed: $uiError" }
                state = DayFiguresState(isLoading = false, failed = true, overview = null)
            }
        }
    }
}
