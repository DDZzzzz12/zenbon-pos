package org.example.project.features.auth.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.core.auth.PermissionManager
import org.example.project.core.auth.personLeft
import org.example.project.core.data.remote.ApprovalRequestApiClient
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.features.auth.presentation.state.ApprovalDialogState
import org.example.project.features.auth.presentation.state.ApprovalRequestUiState

/**
 * ViewModel for the manager-side approval request workflow.
 *
 * Manages pending approval requests, handles approve/deny actions,
 * and displays the approval dialog on incoming WebSocket events.
 *
 * Root-scoped (`MainScreen` obtains it with `koinViewModel()` and has no NavHost entry above it), so
 * it outlives a sign-out. Everything it holds belongs to the person signed in: when that person
 * leaves, the state is dropped ([clearWhenThePersonLeaves]) and an answer still in flight for them
 * is ignored, so the next person starts empty and sees only what the server sends them.
 */
class ApprovalRequestViewModel(
    private val apiClient: ApprovalRequestApiClient,
    private val permissionManager: PermissionManager,
) : ViewModel() {
    private val logger = Logger.withTag("ApprovalRequestViewModel")

    private val _uiState = MutableStateFlow(ApprovalRequestUiState())
    val uiState: StateFlow<ApprovalRequestUiState> = _uiState.asStateFlow()

    /**
     * Bumped each time the signed-in person leaves. A request started under an older value answers
     * for someone who is gone, so its result is dropped instead of landing in the next person's state.
     * The request itself is not cancelled: an approve or deny the manager already pressed still
     * reaches the server.
     */
    private var personGeneration = 0

    init {
        clearWhenThePersonLeaves()
    }

    /** Drops the previous person's requests, dialog and error the moment they sign out or are replaced. */
    private fun clearWhenThePersonLeaves() {
        viewModelScope.launch {
            permissionManager.signedInPerson.personLeft().collect {
                personGeneration++
                _uiState.value = ApprovalRequestUiState()
            }
        }
    }

    private fun isForAPersonWhoLeft(startedIn: Int): Boolean = startedIn != personGeneration

    fun loadPendingRequests() {
        val startedIn = personGeneration
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatchingApi { apiClient.getPendingRequests() }
                .onSuccess { data ->
                    if (isForAPersonWhoLeft(startedIn)) return@onSuccess
                    _uiState.update { it.copy(pendingRequests = data, isLoading = false) }
                }.onError { uiError ->
                    if (isForAPersonWhoLeft(startedIn)) return@onError
                    logger.e { "Failed to load pending requests: $uiError" }
                    _uiState.update { it.copy(isLoading = false, error = uiError) }
                }
        }
    }

    fun approveRequest(
        requestId: String,
        onSuccess: () -> Unit,
    ) {
        val startedIn = personGeneration
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            runCatchingApi { apiClient.approveRequest(requestId) }
                .onSuccess {
                    logger.i { "Approved request: $requestId" }
                    if (isForAPersonWhoLeft(startedIn)) return@onSuccess
                    _uiState.update { state ->
                        state.copy(
                            pendingRequests = state.pendingRequests.filter { it.requestId != requestId },
                            isLoading = false,
                            currentDialog = null,
                        )
                    }
                    onSuccess()
                }.onError { uiError ->
                    logger.e { "Failed to approve request $requestId: $uiError" }
                    if (isForAPersonWhoLeft(startedIn)) return@onError
                    _uiState.update { it.copy(isLoading = false, error = uiError, currentDialog = null) }
                }
        }
    }

    fun denyRequest(
        requestId: String,
        reason: String,
        onSuccess: () -> Unit,
    ) {
        val startedIn = personGeneration
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            runCatchingApi { apiClient.denyRequest(requestId, reason) }
                .onSuccess {
                    logger.i { "Denied request: $requestId" }
                    if (isForAPersonWhoLeft(startedIn)) return@onSuccess
                    _uiState.update { state ->
                        state.copy(
                            pendingRequests = state.pendingRequests.filter { it.requestId != requestId },
                            isLoading = false,
                            currentDialog = null,
                        )
                    }
                    onSuccess()
                }.onError { uiError ->
                    logger.e { "Failed to deny request $requestId: $uiError" }
                    if (isForAPersonWhoLeft(startedIn)) return@onError
                    _uiState.update { it.copy(isLoading = false, error = uiError, currentDialog = null) }
                }
        }
    }

    /**
     * Called when a WebSocket APPROVAL_REQUEST event arrives.
     * Adds to pending list and shows the dialog.
     */
    fun handleIncomingApprovalRequest(request: AuthDto.ApprovalRequestDto) {
        _uiState.update { state ->
            val updated = state.pendingRequests.toMutableList()
            if (updated.none { it.requestId == request.requestId }) {
                updated.add(0, request)
            }
            state.copy(
                pendingRequests = updated,
                currentDialog = ApprovalDialogState.ShowRequest(request),
            )
        }
    }

    fun showDenyConfirmation(request: AuthDto.ApprovalRequestDto) {
        _uiState.update { it.copy(currentDialog = ApprovalDialogState.DenyConfirmation(request)) }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(currentDialog = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
