package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.remote.ShopConfigurationApiClient
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

/** adminApp is single-tenant, matching the other config ViewModels. */
private const val DEFAULT_BROWSER_STATIONS_SHOP_ID: Long = 1L

/**
 * ViewModel for the Browser-Stationen section — which terminal roles may run in a web browser.
 *
 * The server enforces the same allow-list and `CONFIG_WRITE`; this only drives the switches.
 */
class BrowserStationsViewModel(
    private val shopConfigurationApiClient: ShopConfigurationApiClient,
    private val shopId: Long = DEFAULT_BROWSER_STATIONS_SHOP_ID,
) : ViewModel() {
    private val log = Logger.withTag("browser-stations")

    var state by mutableStateOf(BrowserStationsState())
        private set

    /** Driven by the section on entry — the VM is retained app-wide, so `init` would go stale. */
    fun refresh() {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi { shopConfigurationApiClient.getBrowserStations(shopId) }
                .onSuccess { response ->
                    val roles = response.roles.toSet()
                    state = state.copy(enabledRoles = roles, savedRoles = roles, isLoading = false)
                }.onError(retry = ::refresh) { uiError ->
                    log.e { "load browser stations failed shopId=$shopId" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }

    fun setRoleEnabled(
        role: TerminalRole,
        enabled: Boolean,
    ) {
        if (role !in BROWSER_CAPABLE_ROLES) return
        val roles = if (enabled) state.enabledRoles + role.name else state.enabledRoles - role.name
        state = state.copy(enabledRoles = roles, successMessage = null)
    }

    fun save() {
        if (!state.canSave) return
        log.i { "saving browser stations shopId=$shopId roles=${state.enabledRoles}" }
        state = state.copy(isSaving = true, error = null)
        val request = ShopConfigurationDto.UpdateBrowserStationsRequestDto(roles = state.enabledRoles.sorted())
        viewModelScope.launch {
            runCatchingApi { shopConfigurationApiClient.updateBrowserStations(shopId, request) }
                .onSuccess { response ->
                    val roles = response.roles.toSet()
                    state =
                        state.copy(
                            enabledRoles = roles,
                            savedRoles = roles,
                            isSaving = false,
                            successMessage = AdminStringKeys.SettingsBrowserStationsSaved,
                        )
                }.onError(retry = ::save) { uiError ->
                    log.e { "save browser stations failed shopId=$shopId" }
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
}
