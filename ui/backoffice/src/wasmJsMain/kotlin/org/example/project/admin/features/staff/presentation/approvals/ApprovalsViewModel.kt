package org.example.project.admin.features.staff.presentation.approvals

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.remote.TimeTrackingApiClient
import org.example.project.core.domain.dto.timetracking.TimeApprovalDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

class ApprovalsViewModel(
    private val timeApi: TimeTrackingApiClient,
) : ViewModel() {
    private val log = Logger.withTag("approvals")
    private var employeeId: String? = null

    var state by mutableStateOf<ApprovalsState>(ApprovalsState.Loading)
        private set
    var rejectDraft by mutableStateOf<RejectDraft?>(null)
        private set

    fun load(employeeId: String) {
        this.employeeId = employeeId
        rejectDraft = null
        state = ApprovalsState.Loading
        viewModelScope.launch { fetch(employeeId) }
    }

    private suspend fun fetch(employeeId: String) {
        runCatchingApi { timeApi.getPendingApprovals().filter { it.employeeId == employeeId } }
            .onSuccess { pending -> state = ApprovalsState.Data(pending) }
            .onError { uiError ->
                log.e { "load approvals failed" }
                state = ApprovalsState.Error(uiError)
            }
    }

    private fun reload() {
        val id = employeeId ?: return
        viewModelScope.launch { fetch(id) }
    }

    fun approve(approvalId: String) {
        viewModelScope.launch {
            runCatchingApi { timeApi.approveMonth(approvalId, TimeApprovalDto.ApproveRequestDto(approvalId)) }
                .onSuccess {
                    log.d { "approved $approvalId" }
                    reload()
                }.onError { uiError -> log.e { "approve $approvalId failed: $uiError" } }
        }
    }

    fun openReject(approvalId: String) {
        rejectDraft = RejectDraft(approvalId = approvalId)
    }

    fun onRejectReason(reason: String) {
        rejectDraft = rejectDraft?.copy(reason = reason, error = null, fieldErrors = emptyMap())
    }

    fun closeReject() {
        rejectDraft = null
    }

    fun confirmReject() {
        val d = rejectDraft ?: return
        if (d.reason.isBlank()) {
            rejectDraft =
                d.copy(
                    error = null,
                    fieldErrors = mapOf(RejectField.REASON to AdminStringKeys.ApprovalsErrorReasonRequired),
                    validationAttempt = d.validationAttempt + 1,
                )
            return
        }
        rejectDraft = d.copy(saving = true, error = null, fieldErrors = emptyMap())
        viewModelScope.launch {
            runCatchingApi {
                timeApi.rejectMonth(
                    d.approvalId,
                    TimeApprovalDto.RejectRequestDto(approvalId = d.approvalId, reason = d.reason),
                )
            }.onSuccess {
                log.d { "rejected ${d.approvalId}" }
                rejectDraft = null
                reload()
            }.onError { uiError ->
                log.e { "reject ${d.approvalId} failed" }
                rejectDraft = rejectDraft?.copy(saving = false, error = uiError)
            }
        }
    }
}
