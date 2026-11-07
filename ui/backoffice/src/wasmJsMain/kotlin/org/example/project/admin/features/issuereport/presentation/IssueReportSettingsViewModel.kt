package org.example.project.admin.features.issuereport.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.admin.core.auth.canConfigureIssueReports
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.dto.issuereport.IssueReportDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.PosApiException
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.error.toUiError
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.IssueReportKeys
import org.example.project.features.issuereport.data.remote.IssueReportApiClient

/**
 * Settings → Problem reports: the target repository and the write-only GitHub token.
 *
 * Every action refuses without `ISSUE_REPORT_MANAGE` before touching the network (the server
 * refuses again). The token is taken out of state *before* the request is sent and is never logged;
 * a 409 `UPDATE_TOKEN_KEY_MISSING` becomes [IssueReportSettingsState.tokenKeyMissing], which the
 * section explains in place, rather than an error dialog quoting the server's English.
 *
 * A 400 `ISSUE_REPORT_UPDATE_TOKEN_HOST_MISMATCH` on save goes to
 * [IssueReportSettingsState.settingsSaveError] instead of [IssueReportSettingsState.error]: the
 * section shows it in full under the target fields, where a toast would cut it off.
 *
 * [retry] repeats the action that failed — the save for a failed save, never a reload in its place.
 * A failed token write is not retryable: the token left state before the request did.
 */
class IssueReportSettingsViewModel(
    private val api: IssueReportApiClient,
    private val tokenStore: TokenStore,
) : ViewModel() {
    private val log = Logger.withTag("IssueReportSettingsVM")

    private val _state = MutableStateFlow(IssueReportSettingsState())
    val state: StateFlow<IssueReportSettingsState> = _state.asStateFlow()

    /** What [retry] repeats; set together with each failure, null when that failure cannot be retried. */
    private var retryLast: (() -> Unit)? = null

    /** Called from the section's `LaunchedEffect`, not `init`: the VM outlives a logout. */
    fun load() {
        if (!guard()) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, canManage = true) }
            runCatchingApi { api.settings() }
                .onSuccess { settings ->
                    log.d { "settings loaded: ${settings.owner}/${settings.repo} public=${settings.isPublic}" }
                    // A reload resets the draft, so a refusal of the old draft no longer applies.
                    _state.update { it.withSettings(settings).copy(isLoading = false, settingsSaveError = null) }
                }.onError(retry = ::load) { error ->
                    log.w { "settings load failed: ${error.code}" }
                    failed(retryWith = ::load) { it.copy(isLoading = false, error = error) }
                }
        }
    }

    fun onOwner(value: String) = _state.update { it.copy(owner = value) }

    fun onRepo(value: String) = _state.update { it.copy(repo = value) }

    fun onApiBaseUrl(value: String) = _state.update { it.copy(apiBaseUrl = value) }

    fun onPublic(value: Boolean) = _state.update { it.copy(isPublic = value) }

    /** Ignored when it would turn the choice on without a usable update token (the server refuses it too). */
    fun onUseUpdateToken(value: Boolean) {
        if (value && !_state.value.canToggleUpdateToken) return
        _state.update { it.copy(useUpdateToken = value, tokenDraft = if (value) "" else it.tokenDraft) }
    }

    fun onTokenDraft(value: String) = _state.update { it.copy(tokenDraft = value) }

    fun saveSettings() {
        if (!guard() || !_state.value.canSaveSettings) return
        val current = _state.value
        val request =
            IssueReportDto.UpdateSettingsRequestDto(
                owner = current.owner.trim(),
                repo = current.repo.trim(),
                apiBaseUrl = current.apiBaseUrl.trim(),
                isPublic = current.isPublic,
                useUpdateToken = current.useUpdateToken,
            )
        log.i { "saving target ${request.owner}/${request.repo} public=${request.isPublic} updateToken=${request.useUpdateToken}" }
        _state.update { it.copy(settingsSaveError = null) }
        runWrite("saveSettings", IssueReportKeys.settingsSaved, adoptAsDraft = true, retryWith = ::saveSettings) {
            api.saveSettings(request)
        }
    }

    fun saveToken() {
        if (!guard() || !_state.value.canSaveToken) return
        val token = _state.value.tokenDraft.trim()
        // Out of state before the request leaves: whatever happens next, nothing retains it.
        _state.update { it.copy(tokenDraft = "") }
        log.i { "storing a new problem-report token" }
        // Not retryable: the token is already out of state, so a retry would have nothing to send.
        runWrite("saveToken", null, adoptAsDraft = false, retryWith = null) { api.setToken(token) }
    }

    fun clearToken() {
        if (!guard() || !_state.value.canClearToken) return
        log.i { "removing the problem-report token" }
        runWrite("clearToken", null, adoptAsDraft = false, retryWith = ::clearToken) { api.clearToken() }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Repeats the action whose failure is being shown; a no-op when it cannot be repeated. */
    fun retry() {
        val action = retryLast ?: return
        retryLast = null
        action()
    }

    fun clearSuccessMessage() = _state.update { it.copy(successMessage = null) }

    private fun guard(): Boolean {
        if (tokenStore.canConfigureIssueReports) return true
        log.w { "refused: this principal does not hold ISSUE_REPORT_MANAGE" }
        _state.update { it.copy(canManage = false) }
        return false
    }

    private fun runWrite(
        action: String,
        successMessage: StringKey?,
        adoptAsDraft: Boolean,
        retryWith: (() -> Unit)?,
        call: suspend () -> IssueReportDto.SettingsDto,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, successMessage = null) }
            try {
                val settings = call()
                log.i { "$action succeeded" }
                retryLast = null
                _state.update {
                    // A token write must not discard target edits the operator has not saved yet.
                    val next = if (adoptAsDraft) it.withSettings(settings) else it.copy(settings = settings)
                    next.copy(isBusy = false, tokenKeyMissing = false, settingsSaveError = null, successMessage = successMessage)
                }
            } catch (e: PosApiException) {
                log.w { "$action refused: ${e.code}" }
                failed(retryWith) { it.withRefusal(e) }
            } catch (e: NetworkApiException) {
                log.w(e) { "$action failed to reach the server" }
                failed(retryWith) { it.copy(isBusy = false, error = UiError.Network()) }
            }
        }
    }

    private fun failed(
        retryWith: (() -> Unit)?,
        next: (IssueReportSettingsState) -> IssueReportSettingsState,
    ) {
        retryLast = retryWith
        _state.update { next(it).copy(retryable = retryWith != null) }
    }

    /**
     * Routes a refusal to where the section explains it: the missing key and the update-token host
     * rule are shown in place (neither raises the watcher); everything else goes to [IssueReportSettingsState.error].
     */
    private fun IssueReportSettingsState.withRefusal(e: PosApiException): IssueReportSettingsState =
        when (e.code) {
            PosErrorCode.UPDATE_TOKEN_KEY_MISSING -> {
                copy(isBusy = false, tokenKeyMissing = true, error = null)
            }

            PosErrorCode.ISSUE_REPORT_UPDATE_TOKEN_HOST_MISMATCH -> {
                copy(isBusy = false, tokenKeyMissing = false, settingsSaveError = e.toUiError(), error = null)
            }

            else -> {
                copy(isBusy = false, tokenKeyMissing = false, error = e.toUiError())
            }
        }

    /** Adopts [settings] as both the stored value and a fresh, clean draft. */
    private fun IssueReportSettingsState.withSettings(settings: IssueReportDto.SettingsDto) =
        copy(
            settings = settings,
            owner = settings.owner,
            repo = settings.repo,
            apiBaseUrl = settings.apiBaseUrl,
            isPublic = settings.isPublic,
            useUpdateToken = settings.useUpdateToken,
        )
}
