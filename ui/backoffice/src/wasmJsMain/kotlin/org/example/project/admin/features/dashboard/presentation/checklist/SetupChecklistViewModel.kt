package org.example.project.admin.features.dashboard.presentation.checklist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.features.setup.data.remote.SetupApiClient

/** State for the dashboard readiness card. */
data class SetupChecklistState(
    val items: List<SetupChecklistItem> = emptyList(),
    val isLoading: Boolean = false,
    /** True once a load has completed, so the card can stay hidden until it knows something. */
    val loaded: Boolean = false,
)

/**
 * ViewModel for the dashboard readiness card.
 *
 * **A failure renders nothing rather than an error state.** A setup checklist that cannot load is not
 * worth interrupting the dashboard for, and `AdminDashboardScreen` already surfaces errors for the data
 * the operator actually came to see. So there is no `error` in the state — the failure is logged and
 * the card simply stays away.
 */
class SetupChecklistViewModel(
    private val setupApiClient: SetupApiClient,
) : ViewModel() {
    private val log = Logger.withTag("setup-checklist")

    var state by mutableStateOf(SetupChecklistState())
        private set

    /**
     * Driven by the dashboard on entry — the VM is retained app-wide, so `init` would go stale.
     *
     * @param canAdministerTse passed in rather than read here: the VM has no session dependency, and
     *   the role decides whether the TSE row is offered as a click at all.
     */
    fun refresh(canAdministerTse: Boolean) {
        state = state.copy(isLoading = true)
        viewModelScope.launch {
            runCatchingApi { setupApiClient.getReadiness() }
                .onSuccess { readiness ->
                    state =
                        SetupChecklistState(
                            items = buildSetupChecklist(readiness, canAdministerTse),
                            isLoading = false,
                            loaded = true,
                        )
                }.onError { uiError ->
                    // Deliberately not surfaced: see the class doc.
                    log.w { "readiness load failed, hiding the checklist: $uiError" }
                    state = SetupChecklistState(items = emptyList(), isLoading = false, loaded = true)
                }
        }
    }
}
