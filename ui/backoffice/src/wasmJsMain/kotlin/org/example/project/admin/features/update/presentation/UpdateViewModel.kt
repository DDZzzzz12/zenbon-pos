package org.example.project.admin.features.update.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.admin.core.auth.holds
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.dto.update.UpdateDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.update.UpdateBlockedReason
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.PosApiException
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.error.toUiError
import org.example.project.features.update.data.remote.UpdateApiClient

/**
 * The back-office update card.
 *
 * ### The `UPDATE_MANAGE` gate lives in the actions, not only in the screen
 *
 * Every mutating method refuses before it touches the network. Disabling the buttons is the visible
 * half and it is not enough: a disabled control is one refactor away from being re-enabled, and a
 * test of a predicate cannot see that nobody called it. Gating here means the guard is on the path
 * a test actually drives — `checkNow()` on a principal without the capability makes no request at
 * all. The server enforces the same capability on every one of these routes; neither half
 * substitutes for the other.
 *
 * ### A dropped connection during apply is success, not failure
 *
 * `POST /apply` writes its response and then swaps the jar and exits, so the socket dies mid-flight.
 * Reporting that as a network error would tell an operator the install failed at the exact moment
 * it began. [applyNow] therefore treats [NetworkApiException] as "under way" and leaves the screen
 * telling them to wait for the restart.
 */
class UpdateViewModel(
    private val api: UpdateApiClient,
    private val tokenStore: TokenStore,
) : ViewModel() {
    private val log = Logger.withTag("UpdateVM")

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var progressJob: Job? = null

    /**
     * Loads status and settings. Called from the screen's `LaunchedEffect`, not from `init`: the
     * console shares one ViewModelStoreOwner, so a retained instance would otherwise show the
     * previous session's answer after a re-login.
     */
    fun load() =
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, canManage = mayManage()) }
            val status = runCatchingApi { api.status() }
            val settings = runCatchingApi { api.settings() }
            _state.update {
                it.copy(
                    status = status.getOrNull() ?: it.status,
                    settings = settings.getOrNull() ?: it.settings,
                    isLoading = false,
                )
            }
            listOf(status, settings).firstOrNull { it.isFailure }?.onError { error -> _state.update { it.copy(error = error) } }
            // A download already under way (started from another page, or by the nightly check, or
            // by this page before a reload) is only visible if someone asks the server about it.
            if (status.getOrNull()?.download != null) watchProgress()
        }

    fun checkNow() = runManaged(UpdateAction.CHECK) { api.checkNow() }

    fun stageNow() = runManaged(UpdateAction.STAGE) { api.stageNow() }

    fun saveToken(token: String) {
        if (token.isBlank()) return
        runManagedSettings(UpdateAction.SAVE_TOKEN) { api.setToken(token) }
    }

    fun clearToken() = runManagedSettings(UpdateAction.CLEAR_TOKEN) { api.clearToken() }

    fun setAutoApply(enabled: Boolean) = saveSettings { it.copy(autoApply = enabled) }

    fun setChannel(channel: String) = saveSettings { it.copy(channel = channel) }

    /**
     * Installs the staged release. The refusal path is the interesting one: a 409 carries the
     * preflight blocker as a `FieldError`, which is the only way the screen can name *which*
     * condition stands in the way rather than saying the install failed.
     */
    fun applyNow() {
        if (!guard()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = UpdateAction.APPLY, isBlocked = false, blockedReason = null, error = null) }
            try {
                val started = api.applyNow()
                log.i { "apply started for ${started.version}" }
                _state.update { it.copy(busy = null, applyingVersion = started.version) }
            } catch (e: NetworkApiException) {
                // The server exited mid-response. That IS the success path.
                log.i { "apply connection dropped, which is how a successful apply ends" }
                _state.update { it.copy(busy = null, applyingVersion = it.status?.availableVersion ?: "") }
            } catch (e: PosApiException) {
                log.w(e) { "apply refused: ${e.code}" }
                val blocked = e.code == PosErrorCode.UPDATE_BLOCKED
                _state.update {
                    it.copy(
                        busy = null,
                        isBlocked = blocked,
                        blockedReason = if (blocked) blockerOf(e) else null,
                        // The card renders the blocker itself; escalating it to the error dialog as
                        // well would tell the operator the same thing twice, the second time in
                        // server English.
                        error = if (blocked) null else e.toUiError(),
                    )
                }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** The named blocker the 409 carries, or null when this build does not recognise the name. */
    private fun blockerOf(e: PosApiException): UpdateBlockedReason? = UpdateBlockedReason.parse(e.fieldErrors.firstOrNull { it.field == PREFLIGHT_FIELD }?.code)

    private fun mayManage(): Boolean = tokenStore.holds(Capability.UPDATE_MANAGE)

    /**
     * Refuses the whole operation when the principal lacks `UPDATE_MANAGE`, and records the refusal
     * in state so the screen can explain it rather than appearing inert.
     */
    private fun guard(): Boolean {
        if (mayManage()) return true
        log.w { "refused: this principal does not hold UPDATE_MANAGE" }
        _state.update { it.copy(canManage = false) }
        return false
    }

    private fun runManaged(
        action: UpdateAction,
        call: suspend () -> UpdateDto.CheckResultDto,
    ) {
        if (!guard()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = action, isBlocked = false, blockedReason = null) }
            if (action == UpdateAction.STAGE) watchProgress()
            runCatchingApi { call() }
                .onSuccess { result ->
                    log.i { "${action.name}: ${result.summary}" }
                    _state.update { it.copy(busy = null, status = result.status) }
                }.onError { error -> _state.update { it.copy(busy = null, error = error) } }
        }
    }

    private fun runManagedSettings(
        action: UpdateAction,
        call: suspend () -> UpdateDto.SettingsDto,
    ) {
        if (!guard()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = action) }
            try {
                val settings = call()
                _state.update { it.copy(busy = null, settings = settings, tokenKeyMissing = false) }
                refreshStatus()
            } catch (e: PosApiException) {
                log.w(e) { "${action.name} refused: ${e.code}" }
                val keyMissing = e.code == PosErrorCode.UPDATE_TOKEN_KEY_MISSING
                _state.update {
                    it.copy(
                        busy = null,
                        tokenKeyMissing = keyMissing,
                        error = if (keyMissing) null else e.toUiError(),
                    )
                }
            } catch (e: NetworkApiException) {
                log.w(e) { "${action.name} failed to reach the server" }
                _state.update { it.copy(busy = null, error = UiError.Network()) }
            }
        }
    }

    private fun saveSettings(edit: (UpdateDto.SettingsDto) -> UpdateDto.SettingsDto) {
        val current = _state.value.settings ?: return
        val next = edit(current)
        runManagedSettings(UpdateAction.SAVE_SETTINGS) {
            api.saveSettings(
                UpdateDto.UpdateSettingsRequestDto(
                    channel = next.channel,
                    apiBaseUrl = next.apiBaseUrl,
                    autoApply = next.autoApply,
                ),
            )
        }
    }

    /**
     * Polls the status while a download runs, so the screen can show how far it is.
     *
     * Stage is one long request with nothing to report until it returns, so progress has to be read
     * from the side. The loop outlives the request that started it on purpose: if that request dies
     * (a timeout, a reload) the server keeps downloading, and this is what still tells the operator
     * when the artifact is staged -- without it the Install button stays disabled on a stale
     * `staged = false` until they happen to reload the page.
     *
     * One loop at most; a transient failure of a single poll is skipped, and a run of them ends it
     * rather than spinning against a server that is gone.
     */
    private fun watchProgress() {
        if (progressJob?.isActive == true) return
        progressJob =
            viewModelScope.launch {
                var failures = 0
                while (true) {
                    delay(PROGRESS_POLL_MS)
                    val polled = runCatchingApi { api.status() }.getOrNull()
                    if (polled == null) {
                        if (++failures >= MAX_POLL_FAILURES && _state.value.busy != UpdateAction.STAGE) break
                        continue
                    }
                    failures = 0
                    _state.update { it.copy(status = polled) }
                    if (polled.download == null && _state.value.busy != UpdateAction.STAGE) break
                }
                // The last poll may predate the end of the run; read the settled state once more.
                refreshStatus()
            }
    }

    /** The settings write moves what the status read reports (channel, auto-apply), so re-read it. */
    private suspend fun refreshStatus() {
        runCatchingApi { api.status() }.onSuccess { status -> _state.update { it.copy(status = status) } }
    }

    private companion object {
        const val PREFLIGHT_FIELD = "preflight"
        const val PROGRESS_POLL_MS = 1_000L
        const val MAX_POLL_FAILURES = 5
    }
}
