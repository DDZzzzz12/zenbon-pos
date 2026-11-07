package org.example.project.admin.features.dashboard.presentation.sections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.TseQueueApiClient
import org.example.project.core.data.remote.TseSettingsApiClient
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

/**
 * `/api/tse/health` requires `TSE_ADMIN` and `/api/tse/queue/status` requires `TSE_QUEUE_ADMIN`. The
 * card is composed only for a principal holding both, so neither call is ever issued by somebody the
 * server would refuse — the gate is a composition decision, not a caught 403.
 */
class TseHealthViewModel(
    private val tseSettingsApiClient: TseSettingsApiClient,
    private val tseQueueApiClient: TseQueueApiClient,
) : ViewModel() {
    private val log = Logger.withTag("dashboard-tse-health")

    var state by mutableStateOf(TseHealthState())
        private set

    fun refresh() {
        state = state.copy(isLoading = true, failed = false)
        viewModelScope.launch {
            runCatchingApi {
                coroutineScope {
                    val health = async { tseSettingsApiClient.getHealth() }
                    val queue = async { tseQueueApiClient.getQueueStatus() }
                    health.await() to queue.await()
                }
            }.onSuccess { (health, queue) ->
                state =
                    TseHealthState(
                        isLoading = false,
                        failed = false,
                        healthy = health.healthy && health.validTime && health.passedSelfTest,
                        unsignedCount = queue.failedCount + queue.queuedCount,
                    )
            }.onError { uiError ->
                log.w { "tse health load failed: $uiError" }
                state = TseHealthState(isLoading = false, failed = true)
            }
        }
    }
}
