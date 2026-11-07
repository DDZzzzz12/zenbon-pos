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
 * Where the fiscal day stands: when it was last closed, and what still blocks closing it.
 *
 * This replaces the five-row Z-Bon list the dashboard used to carry. The list is a sidebar
 * destination of its own under Kasse & Abschluss; two views of it on one screen was one too many.
 */
class DayCloseViewModel(
    private val zBonApiClient: ZBonApiClient,
    private val statisticsApiClient: StatisticsApiClient,
) : ViewModel() {
    private val log = Logger.withTag("dashboard-day-close")

    var state by mutableStateOf(DayCloseState())
        private set

    fun refresh() {
        state = state.copy(isLoading = true, failed = false)
        viewModelScope.launch {
            runCatchingApi {
                val lastEnd = zBonApiClient.getLatestZBonEndTime()
                val start = getZBonStartTime(zBonApiClient)
                val end =
                    Clock.System
                        .now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                        .toString()
                val sessions =
                    statisticsApiClient.getPaginatedSessions(
                        startDate = start,
                        endDate = end,
                        completed = false,
                    )
                lastEnd to sessions.sessions.size
            }.onSuccess { (lastEnd, openCount) ->
                state =
                    DayCloseState(
                        isLoading = false,
                        failed = false,
                        lastZBonEndTime = lastEnd,
                        openSessionCount = openCount,
                    )
            }.onError { uiError ->
                log.w { "day close load failed: $uiError" }
                state = DayCloseState(isLoading = false, failed = true)
            }
        }
    }
}
