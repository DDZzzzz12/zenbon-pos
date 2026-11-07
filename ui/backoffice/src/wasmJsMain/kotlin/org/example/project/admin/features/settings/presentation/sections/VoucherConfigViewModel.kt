package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.EcrConfigurationApiClient
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.models.setup.VoucherConfig
import org.example.project.core.domain.models.setup.VoucherSource
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.features.settings.presentation.state.VoucherConfigManagementState

/**
 * ViewModel for the adminApp Gutscheine section — the voucher master switch and its source list.
 *
 * composeApp's `VoucherConfigViewModel` stays in composeApp: it takes `ShopDataManager` (the till's
 * local shop-identity cache) and a `DeviceSettingsMirror` it writes to so a till can honour voucher
 * settings offline. Neither has meaning in a web admin, and `ShopDataManager` cannot cross to wasm.
 * Everything else it does is `EcrConfigurationApiClient` calls, which this reproduces — including
 * reusing the same [VoucherConfigManagementState] shared-ui's `VoucherConfigManagementContent`
 * renders, so the two clients cannot drift.
 *
 * The shop id is fixed at [SHOP_ID]: this system is single-tenant, and composeApp's own code already
 * falls back to `1L` whenever its cache has no value.
 */
class VoucherConfigViewModel(
    private val ecrConfigurationApiClient: EcrConfigurationApiClient,
) : ViewModel() {
    private companion object {
        const val SHOP_ID = 1L
    }

    private val log = Logger.withTag("vouchers")

    var state by mutableStateOf(VoucherConfigManagementState())
        private set

    /** Driven by the section on entry — the VM is retained app-wide, so `init` would go stale. */
    fun refresh() {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi { ecrConfigurationApiClient.getVoucherConfiguration(SHOP_ID) }
                .onSuccess { response ->
                    state = state.copy(voucherConfig = response.voucherConfig, isLoading = false)
                }.onError(retry = ::refresh) { uiError ->
                    log.e { "load voucher config failed" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    fun showAddDialog() {
        state = state.copy(showAddDialog = true, editingSource = null)
    }

    fun showEditDialog(source: VoucherSource) {
        state = state.copy(showAddDialog = true, editingSource = source)
    }

    fun hideDialog() {
        state = state.copy(showAddDialog = false, editingSource = null)
    }

    fun setEnabled(enabled: Boolean) {
        val current = state.voucherConfig ?: return
        log.i { "voucher system enabled=$enabled" }
        save(current.copy(enabled = enabled))
    }

    /**
     * Adds a source, or replaces the one with the same [type] — `type` is the identity of a source,
     * so an edit that changed it would otherwise silently create a duplicate.
     */
    fun upsertSource(
        type: VoucherSourceType,
        name: String,
        enabled: Boolean,
        description: String?,
    ) {
        val current = state.voucherConfig ?: return
        val replacement = VoucherSource(type = type, name = name, enabled = enabled, description = description)
        val editingType = state.editingSource?.type
        val sources =
            current.sources
                .filterNot { it.type == type || it.type == editingType }
                .plus(replacement)
        log.i { "upsert voucher source type=$type" }
        save(current.copy(sources = sources))
    }

    fun deleteSource(type: VoucherSourceType) {
        val current = state.voucherConfig ?: return
        log.i { "delete voucher source type=$type" }
        save(current.copy(sources = current.sources.filterNot { it.type == type }))
    }

    /**
     * True when [type] cannot be removed: an enabled voucher system must keep an INTERNAL source, or
     * [VoucherConfig.isValid] fails. The server rejects it too — this is the client half of the guard,
     * so the control can be hidden rather than letting the operator discover it via a 422.
     */
    fun isSourceRequired(type: VoucherSourceType): Boolean = type == VoucherSourceType.INTERNAL && state.voucherConfig?.enabled == true

    private fun save(config: VoucherConfig) {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi {
                ecrConfigurationApiClient.updateVoucherConfiguration(
                    SHOP_ID,
                    ShopConfigurationDto.UpdateVoucherConfigRequestDto(voucherConfig = config),
                )
            }.onSuccess {
                hideDialog()
                refresh()
            }.onError { uiError ->
                log.e { "save voucher config failed" }
                state = state.copy(isLoading = false, error = uiError)
            }
        }
    }
}
