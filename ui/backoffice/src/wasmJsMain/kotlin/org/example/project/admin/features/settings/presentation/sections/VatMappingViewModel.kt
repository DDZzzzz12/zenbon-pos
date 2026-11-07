package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.remote.EcrConfigurationApiClient
import org.example.project.core.data.remote.VatApiClient
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.core.domain.models.setup.VatMappingConfig
import org.example.project.core.domain.models.setup.VatRules
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError

/** adminApp is single-tenant, matching the other config ViewModels. */
private const val DEFAULT_SHOP_ID: Long = 1L

/**
 * ViewModel for the MwSt-Zuordnung section — which VAT class each [ProductType] books under, per
 * condition.
 *
 * Restored from composeApp's deleted `VatMappingViewModel` (commit `498867657`). The behaviour is the
 * deleted one; the presentation is rebuilt lean for a desktop browser.
 *
 * The mapping's strings are VAT **primary keys** (`vatId`), which the server restricts to A, B and C —
 * `VatRules.isValid()` and `ConfigurationRoutes`' PUT both enforce it. [vatApiClient] is read only to
 * label those keys with their current percentage.
 */
class VatMappingViewModel(
    private val ecrConfigurationApiClient: EcrConfigurationApiClient,
    private val vatApiClient: VatApiClient,
    private val shopId: Long = DEFAULT_SHOP_ID,
) : ViewModel() {
    private val log = Logger.withTag("vat-mapping")

    var state by mutableStateOf(VatMappingState())
        private set

    /** Driven by the section on entry — the VM is retained app-wide, so `init` would go stale. */
    fun refresh() {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            // Both suspend calls sit INSIDE runCatchingApi's try. `onSuccess` is plain
            // kotlin.Result.onSuccess and runs outside it — a suspend call there is unguarded.
            runCatchingApi {
                val mapping = ecrConfigurationApiClient.getVatMapping(shopId).vatMapping
                mapping to vatApiClient.getAllVatRates()
            }.onSuccess { (mapping, rates) ->
                state = state.copy(mapping = mapping, vatRates = rates, isLoading = false)
            }.onError(retry = ::refresh) { uiError ->
                log.e { "load VAT mapping failed shopId=$shopId" }
                state = state.copy(isLoading = false, error = uiError)
            }
        }
    }

    /**
     * Opens the editor. Falls back to the German default when nothing loaded, so a section entered
     * mid-failure still offers a usable editor instead of an empty dialog.
     */
    fun startEdit() {
        state = state.copy(draft = state.mapping ?: VatMappingConfig.getDefault())
    }

    fun cancelEdit() {
        state = state.copy(draft = null)
    }

    fun updateRules(
        productType: ProductType,
        rules: VatRules,
    ) {
        val draft = state.draft ?: return
        state = state.copy(draft = draft.withRulesFor(productType, rules))
    }

    fun save() {
        val draft = state.draft ?: return
        if (!draft.isValid()) {
            // Fail fast: the server answers 400 for anything outside A/B/C, and a round trip would
            // turn a knowable client-side mistake into an opaque server rejection.
            log.w { "refusing to save an invalid VAT mapping" }
            state =
                state.copy(
                    error =
                        UiError.Unknown(
                            titleKey = AdminStringKeys.SettingsSectionVatMapping,
                            messageKey = AdminStringKeys.SettingsVatMappingInvalid,
                        ),
                )
            return
        }
        log.i { "saving VAT mapping shopId=$shopId" }
        state = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            runCatchingApi {
                ecrConfigurationApiClient.updateVatMapping(
                    shopId,
                    ShopConfigurationDto.UpdateVatMappingRequestDto(vatMapping = draft),
                )
            }.onSuccess { response ->
                state =
                    state.copy(
                        mapping = response.vatMapping,
                        draft = null,
                        isSaving = false,
                        successMessage = AdminStringKeys.SettingsVatMappingSaved,
                    )
            }.onError { uiError ->
                log.e { "save VAT mapping failed shopId=$shopId" }
                // The draft stays open so the operator's edit is not thrown away by a transient failure.
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

/**
 * [VatMappingConfig] names its six product types as separate properties rather than holding a map, so
 * a per-type update needs this explicit fan-out. Exhaustive `when`: adding a [ProductType] breaks the
 * build here, which is the intent.
 */
private fun VatMappingConfig.withRulesFor(
    productType: ProductType,
    rules: VatRules,
): VatMappingConfig =
    when (productType) {
        ProductType.FOOD -> copy(FOOD = rules)
        ProductType.DRINKS -> copy(DRINKS = rules)
        ProductType.RETAIL -> copy(RETAIL = rules)
        ProductType.SERVICE -> copy(SERVICE = rules)
        ProductType.VOUCHER -> copy(VOUCHER = rules)
        ProductType.OTHERS -> copy(OTHERS = rules)
    }
