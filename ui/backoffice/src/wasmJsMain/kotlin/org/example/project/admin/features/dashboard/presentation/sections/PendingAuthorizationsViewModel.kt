package org.example.project.admin.features.dashboard.presentation.sections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.ApprovalRequestApiClient
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

/**
 * The till's live authorization requests: a cashier is blocked and waiting for a manager.
 *
 * The card acts rather than counts. These requests expire within minutes, so a tile that says "2"
 * and makes a manager navigate somewhere has already cost more time than it saved.
 *
 * **Not `ApprovalsViewModel`** — that name belongs to the staff timesheet approvals, and on wasm the
 * Koin ViewModel store keys off the simple class name, so two classes sharing one name share one
 * store entry and throw `ClassCastException` (the bug fixed in `ade3ddfcd`).
 *
 * Every write ends in a re-read rather than a local edit: the server decides whether a request was
 * still pending, and a list that disagrees with it would offer a decision already made.
 */
class PendingAuthorizationsViewModel(
    private val api: ApprovalRequestApiClient,
) : ViewModel() {
    private val log = Logger.withTag("dashboard-authorizations")

    var state by mutableStateOf(PendingAuthorizationsState())
        private set

    fun refresh() {
        state = state.copy(isLoading = true, failed = false)
        viewModelScope.launch { fetch() }
    }

    fun startDeny(requestId: String) {
        state = state.copy(denyingRequestId = requestId)
    }

    fun cancelDeny() {
        state = state.copy(denyingRequestId = null)
    }

    fun approve(requestId: String) {
        viewModelScope.launch {
            runCatchingApi { api.approveRequest(requestId) }
                .onError { uiError -> log.w { "approve $requestId failed: $uiError" } }
            fetch()
        }
    }

    fun deny(
        requestId: String,
        reason: String,
        blankReasonText: String,
    ) {
        viewModelScope.launch {
            runCatchingApi { api.denyRequest(requestId, reason.ifBlank { blankReasonText }) }
                .onError { uiError -> log.w { "deny $requestId failed: $uiError" } }
            state = state.copy(denyingRequestId = null)
            fetch()
        }
    }

    private suspend fun fetch() {
        runCatchingApi { api.getPendingRequests() }
            .onSuccess { requests ->
                state = state.copy(isLoading = false, failed = false, requests = requests)
            }.onError { uiError ->
                log.w { "pending authorizations load failed: $uiError" }
                state = state.copy(isLoading = false, failed = true, requests = emptyList())
            }
    }
}
