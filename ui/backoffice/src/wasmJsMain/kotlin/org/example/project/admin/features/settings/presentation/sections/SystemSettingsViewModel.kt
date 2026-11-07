package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.remote.WebAccessApiClient
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

/** adminApp is single-tenant, matching the other config ViewModels. */
private const val DEFAULT_SYSTEM_SETTINGS_SHOP_ID: Long = 1L

/**
 * ViewModel for the System section — the API-docs switch and the default page for `/`.
 *
 * The server enforces `CONFIG_WRITE`, the `POS_API_DOCS` lock and the `pos` availability on the PUT;
 * this only drives the controls and refuses the same edits up front.
 */
class SystemSettingsViewModel(
    private val webAccessApiClient: WebAccessApiClient,
    private val shopId: Long = DEFAULT_SYSTEM_SETTINGS_SHOP_ID,
) : ViewModel() {
    private val log = Logger.withTag("system-settings")

    var state by mutableStateOf(SystemSettingsState())
        private set

    /** Driven by the section on entry — the VM is retained app-wide, so `init` would go stale. */
    fun refresh() {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi { webAccessApiClient.getWebAccess(shopId) }
                .onSuccess { response ->
                    state = state.withServerState(response).copy(isLoading = false)
                }.onError(retry = ::refresh) { uiError ->
                    log.e { "load web access failed shopId=$shopId" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }

    fun setApiDocs(enabled: Boolean) {
        if (state.apiDocsLocked) return
        state = state.copy(apiDocsSetting = enabled, successMessage = null)
    }

    fun setStartPageDefault(target: String) {
        if (target !in state.availableTargets) return
        state = state.copy(startPageDefault = target, successMessage = null)
    }

    fun save() {
        if (!state.canSave) return
        if (state.fieldErrors.isNotEmpty()) {
            // The stored target is no longer offered: refuse up front (the server would 400) and let the
            // section reveal the start-page choice, which already says why.
            log.w { "refusing to save an unavailable start page ${state.startPageDefault}" }
            state = state.copy(validationAttempt = state.validationAttempt + 1, successMessage = null)
            return
        }
        log.i { "saving web access shopId=$shopId apiDocs=${state.apiDocsSetting} startPage=${state.startPageDefault}" }
        val request =
            ShopConfigurationDto.UpdateWebAccessRequestDto(
                apiDocsEnabled = state.apiDocsSetting,
                startPageDefault = state.startPageDefault,
            )
        state = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            runCatchingApi { webAccessApiClient.updateWebAccess(shopId, request) }
                .onSuccess { response ->
                    state =
                        state.withServerState(response).copy(
                            isSaving = false,
                            successMessage = AdminStringKeys.SettingsSystemSaved,
                        )
                }.onError(retry = ::save) { uiError ->
                    log.e { "save web access failed shopId=$shopId" }
                    state = state.copy(isSaving = false, error = uiError)
                }
        }
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    fun clearSuccessMessage() {
        state = state.copy(successMessage = null)
    }

    private fun SystemSettingsState.withServerState(response: ShopConfigurationDto.WebAccessResponseDto): SystemSettingsState =
        copy(
            apiDocsSetting = response.apiDocsSetting,
            savedApiDocsSetting = response.apiDocsSetting,
            apiDocsEnabled = response.apiDocsEnabled,
            apiDocsLocked = response.apiDocsLockedByServerConfig,
            startPageDefault = response.startPageDefault,
            savedStartPageDefault = response.startPageDefault,
            availableTargets = response.availableStartPageTargets,
        )
}
