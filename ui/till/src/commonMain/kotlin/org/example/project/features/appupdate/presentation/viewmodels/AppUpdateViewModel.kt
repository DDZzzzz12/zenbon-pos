package org.example.project.features.appupdate.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.core.domain.dto.update.UpdateDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.features.appupdate.domain.AppUpdateDecision
import org.example.project.features.appupdate.domain.AppUpdateException
import org.example.project.features.appupdate.domain.AppUpdatePlatform
import org.example.project.features.appupdate.domain.AppUpdateState
import org.example.project.features.appupdate.domain.isBusy
import org.example.project.features.update.data.remote.AppUpdateApiClient

/**
 * Drives the till's self-update: check -> prompt -> download -> install.
 *
 * Server errors surface as [UiError] for `PosErrorWatcher`, per ui/till/CLAUDE.md. Failures that
 * happen on the device (hash mismatch, install cancelled, ...) are not server errors and cannot be
 * a `UiError`; they live in [AppUpdateState.Failed] and render inside the dialog.
 *
 * Never starts on a platform whose [AppUpdatePlatform.installedVersionCode] is null.
 */
class AppUpdateViewModel(
    private val api: AppUpdateApiClient,
    private val platform: AppUpdatePlatform,
) : ViewModel() {
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private val _error = MutableStateFlow<UiError?>(null)
    val error: StateFlow<UiError?> = _error.asStateFlow()

    /** False on platforms without self-update; the UI then hides the check button entirely. */
    val supportsUpdates: Boolean get() = platform.installedVersionCode() != null

    /**
     * Asks the server whether a newer build is staged.
     *
     * @param manual true from the "Check for updates" button: an up-to-date answer and an
     *   unreachable server are reported. An automatic check (login screen) stays silent for both --
     *   a till behind a flaky Wi-Fi must not greet every login with an error.
     */
    fun check(manual: Boolean = false) {
        val installed = platform.installedVersionCode() ?: return
        if (_state.value.isBusy) return
        _state.value = AppUpdateState.Checking
        viewModelScope.launch {
            runCatchingApi { api.latestAndroidClient() }
                .onSuccess { offered ->
                    // Nothing newer is offered: a downloaded APK left from an install that just succeeded
                    // (the process dies with the replace, so nothing else could have removed it) is dead weight.
                    if (!AppUpdateDecision.isNewer(installed, offered.versionCode)) platform.discardDownload()
                    _state.value = resultOf(installed, offered, manual)
                }.onError { uiError ->
                    val nothingStaged = uiError is UiError.FromServer && uiError.httpStatus == HTTP_NOT_FOUND
                    if (manual && !nothingStaged) _error.value = uiError
                    // Only "nothing staged" is an honest "up to date"; a failed check must not claim it.
                    _state.value = if (manual && nothingStaged) AppUpdateState.UpToDate else AppUpdateState.Idle
                }
        }
    }

    /** Downloads, verifies and installs the build the dialog is showing. */
    fun startUpdate() {
        val info = pendingInfo() ?: return
        viewModelScope.launch {
            try {
                _state.value = AppUpdateState.Downloading(info, downloadedBytes = 0)
                platform.download(api.downloadUrl(), info) { done, _ ->
                    _state.value = AppUpdateState.Downloading(info, done)
                }
                installOrAskPermission(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppUpdateException) {
                _state.value = AppUpdateState.Failed(e.failure, info)
            }
        }
    }

    /** Continues after the user has allowed installs; the file is already on disk. */
    fun resumeInstall() {
        val info = (_state.value as? AppUpdateState.ReadyToInstall)?.info ?: return
        viewModelScope.launch {
            try {
                installOrAskPermission(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppUpdateException) {
                _state.value = AppUpdateState.Failed(e.failure, info)
            }
        }
    }

    fun openPermissionSettings() = platform.openInstallPermissionSettings()

    fun dismiss() {
        platform.discardDownload()
        _state.value = AppUpdateState.Idle
    }

    fun clearError() {
        _error.value = null
    }

    private suspend fun installOrAskPermission(info: UpdateDto.AndroidClientDto) {
        if (!platform.canInstall()) {
            _state.value = AppUpdateState.ReadyToInstall(info, needsPermission = true)
            return
        }
        _state.value = AppUpdateState.Installing(info)
        platform.install()
    }

    private fun resultOf(
        installed: Long,
        offered: UpdateDto.AndroidClientDto,
        manual: Boolean,
    ): AppUpdateState =
        when {
            AppUpdateDecision.isNewer(installed, offered.versionCode) -> AppUpdateState.Available(offered)
            manual -> AppUpdateState.UpToDate
            else -> AppUpdateState.Idle
        }

    /** The build to act on: the one offered, or the one a failed attempt can retry. */
    private fun pendingInfo(): UpdateDto.AndroidClientDto? =
        when (val s = _state.value) {
            is AppUpdateState.Available -> s.info
            is AppUpdateState.Failed -> s.info
            else -> null
        }

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}
