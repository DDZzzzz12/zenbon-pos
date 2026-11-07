package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.ShopInfoApiClient
import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError

/**
 * Editable shop master data.
 *
 * Deliberately adminApp's own rather than shared-ui's `ShopInfoFormState`: that one has no `ustIdNr`
 * field, so composeApp's settings screen cannot edit the VAT ID at all. See [ShopInfoViewModel].
 *
 * [zipCode] is a String here though the DTO carries an `Int?` — a partially typed postcode is not a
 * number, and re-parsing on every keystroke would fight the user.
 */
data class ShopInfoFormFields(
    val shopName: String = "",
    val companyName: String = "",
    val shopAddress: String = "",
    val shopCity: String = "",
    val zipCode: String = "",
    val shopCountry: String = "",
    val shopPhone: String = "",
    val shopEmail: String = "",
    val shopWebsite: String = "",
    val shopTaxNumber: String = "",
    val ustIdNr: String = "",
    val shopBankName: String = "",
    val shopBankBic: String = "",
    val shopBankIBAN: String = "",
    val restaurantMode: Boolean = true,
    val logoUrl: String = "",
)

/** State for the adminApp Geschäftsdaten section. */
data class ShopInfoSectionState(
    val form: ShopInfoFormFields = ShopInfoFormFields(),
    /** What the server last returned; [isDirty] compares the draft against it. */
    val saved: ShopInfoFormFields = ShopInfoFormFields(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isUploadingLogo: Boolean = false,
    val error: UiError? = null,
) {
    /** The draft differs from what was last loaded or saved. Edit-then-undo is clean. */
    val isDirty: Boolean get() = form != saved
}

/**
 * ViewModel for the adminApp Geschäftsdaten section.
 *
 * composeApp's `ShopInfoViewModel` stays in composeApp: it takes a `PermissionManager`, which wraps the
 * till's local database for a JWT-derived role cache and temporary-role escalation. adminApp reads the
 * role straight off `TokenStore`, so the section gates on `canManageProducts` instead and this holds
 * only the API work.
 *
 * **This form includes `ustIdNr`, which composeApp's cannot edit.** The field reached the DTO and the
 * bootstrap wizard in sub-project A — it is read by `DsfinvkExportServiceImpl` (USTID) and the Z-Bon
 * `StammdatenSnapshot` — but shared-ui's `ShopInfoFormState` was never given it, so after setup there
 * was nowhere to correct a mistyped VAT ID. Recorded as a composeApp follow-up.
 */
class ShopInfoViewModel(
    private val shopInfoApiClient: ShopInfoApiClient,
) : ViewModel() {
    private val log = Logger.withTag("shop-info")

    var state by mutableStateOf(ShopInfoSectionState())
        private set

    /** Driven by the section on entry — the VM is retained app-wide, so `init` would go stale. */
    fun refresh() {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi { shopInfoApiClient.getShopInfo() }
                .onSuccess { info ->
                    val loaded = info.toForm()
                    state = state.copy(form = loaded, saved = loaded, isLoading = false)
                }.onError(retry = ::refresh) { uiError ->
                    log.e { "load shop info failed" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    fun updateForm(transform: (ShopInfoFormFields) -> ShopInfoFormFields) {
        state = state.copy(form = transform(state.form))
    }

    fun save() {
        val form = state.form
        state = state.copy(isSaving = true, error = null)
        log.i { "saving shop info" }
        viewModelScope.launch {
            runCatchingApi { shopInfoApiClient.updateShopInfo(request = form.toRequest()) }
                .onSuccess { resp ->
                    log.i { "shop info saved" }
                    val persisted = resp.toForm()
                    state = state.copy(form = persisted, saved = persisted, isSaving = false)
                }.onError { uiError ->
                    log.e { "save shop info failed" }
                    state = state.copy(isSaving = false, error = uiError)
                }
        }
    }

    fun uploadLogo(
        bytes: ByteArray,
        fileName: String,
    ) {
        state = state.copy(isUploadingLogo = true, error = null)
        log.i { "uploading logo $fileName (${bytes.size} bytes)" }
        viewModelScope.launch {
            runCatchingApi { shopInfoApiClient.uploadLogo(bytes, fileName) }
                .onSuccess { resp ->
                    log.i { "logo uploaded" }
                    state =
                        state.copy(
                            form = state.form.copy(logoUrl = resp.logoUrl.orEmpty()),
                            // The upload persists the logo itself, so it is not an unsaved edit.
                            saved = state.saved.copy(logoUrl = resp.logoUrl.orEmpty()),
                            isUploadingLogo = false,
                        )
                }.onError { uiError ->
                    log.e { "logo upload failed" }
                    state = state.copy(isUploadingLogo = false, error = uiError)
                }
        }
    }

    /**
     * Clears the logo by sending an empty `logoUrl`. There is no delete endpoint, and sending null
     * would mean "leave unchanged" — every field in the update DTO is nullable-as-absent.
     */
    fun removeLogo() {
        state = state.copy(isSaving = true, error = null)
        log.i { "removing logo" }
        viewModelScope.launch {
            runCatchingApi {
                shopInfoApiClient.updateShopInfo(
                    request = ShopInfoDto.UpdateShopInfoRequestDto(logoUrl = ""),
                )
            }.onSuccess { resp ->
                val persisted = resp.toForm()
                state = state.copy(form = persisted, saved = persisted, isSaving = false)
            }.onError { uiError ->
                log.e { "remove logo failed" }
                state = state.copy(isSaving = false, error = uiError)
            }
        }
    }
}

private fun ShopInfoDto.ShopInfoResponseDto.toForm() =
    ShopInfoFormFields(
        shopName = shopName,
        companyName = companyName.orEmpty(),
        shopAddress = shopAddress.orEmpty(),
        shopCity = shopCity.orEmpty(),
        zipCode = zipCode?.toString().orEmpty(),
        shopCountry = shopCountry.orEmpty(),
        shopPhone = shopPhone.orEmpty(),
        shopEmail = shopEmail.orEmpty(),
        shopWebsite = shopWebsite.orEmpty(),
        shopTaxNumber = shopTaxNumber.orEmpty(),
        ustIdNr = ustIdNr.orEmpty(),
        shopBankName = shopBankName.orEmpty(),
        shopBankBic = shopBankBic.orEmpty(),
        shopBankIBAN = shopBankIBAN.orEmpty(),
        restaurantMode = restaurantMode,
        logoUrl = logoUrl.orEmpty(),
    )

/**
 * Blank means "clear it", not "leave it alone" — the operator emptied the field on purpose. Only
 * A blank optional field is sent as **null (absent), not as an empty string.** Sending `""` looks like
 * "clear it", but the server format-validates several of these and rejects an empty value — a save with
 * an untouched E-Mail field failed with `Invalid email format:` until this was changed. The server has
 * no representation for "clear this field", so absent is the only safe encoding; the cost is that a
 * field cannot be emptied once set, which is the server's constraint rather than a choice made here.
 *
 * [shopName] is always sent: it is required, and the section only enables Save while it is non-blank.
 */
private fun ShopInfoFormFields.toRequest() =
    ShopInfoDto.UpdateShopInfoRequestDto(
        shopName = shopName.trim(),
        companyName = companyName.orAbsent(),
        shopAddress = shopAddress.orAbsent(),
        shopCity = shopCity.orAbsent(),
        zipCode = zipCode.trim().toIntOrNull(),
        shopCountry = shopCountry.orAbsent(),
        shopPhone = shopPhone.orAbsent(),
        shopEmail = shopEmail.orAbsent(),
        shopWebsite = shopWebsite.orAbsent(),
        shopTaxNumber = shopTaxNumber.orAbsent(),
        ustIdNr = ustIdNr.orAbsent(),
        shopBankName = shopBankName.orAbsent(),
        shopBankBic = shopBankBic.orAbsent(),
        shopBankIBAN = shopBankIBAN.orAbsent(),
        restaurantMode = restaurantMode,
    )

/** Trimmed, or null when blank — see [toRequest] for why blank must not become `""`. */
private fun String.orAbsent(): String? = trim().takeIf { it.isNotEmpty() }
