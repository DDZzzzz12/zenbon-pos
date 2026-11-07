package org.example.project.admin.features.supplier.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.remote.SupplierApiClient
import org.example.project.core.domain.dto.supplier.SupplierDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.PosApiException
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.error.toUiError
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.utils.EmailFormat

/**
 * Drives the supplier detail screen: loads one creditor, tracks the editable [SupplierForm] against
 * its [SupplierDetailState.pristine] snapshot for dirty-tracking, and saves.
 *
 * [save] validates before dispatching — a blank name never reaches the server — and re-pins
 * `pristine` only on a successful `PUT`, so a failed save keeps the screen dirty.
 */
class SupplierDetailViewModel(
    private val api: SupplierApiClient,
) : ViewModel() {
    private val log = Logger.withTag("supplierDetail")

    var state by mutableStateOf(SupplierDetailState())
        private set

    fun load(supplierId: Long) {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi { api.get(supplierId) }
                .onSuccess { dto ->
                    log.d { "supplier $supplierId loaded: provisional=${dto.isProvisional}" }
                    val form = SupplierForm.from(dto)
                    state =
                        state.copy(
                            id = dto.id,
                            isLoading = false,
                            form = form,
                            pristine = form,
                            knownIbans = dto.knownIbans,
                            openTotal = dto.openTotal ?: "0",
                            ytdSpend = dto.ytdSpend ?: "0",
                            currency = dto.currency,
                            isProvisional = dto.isProvisional,
                            nameError = null,
                        )
                }.onError(retry = { load(supplierId) }) { uiError ->
                    log.e { "supplier $supplierId load failed: $uiError" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }

    /** Drops unsaved edits: the form goes back to what was last loaded or saved. */
    fun discardDraft() {
        state = state.copy(form = state.pristine, nameError = null)
    }

    fun onNameChange(value: String) = updateForm { it.copy(name = value) }

    fun onLegalNameChange(value: String) = updateForm { it.copy(legalName = value) }

    fun onVatIdChange(value: String) = updateForm { it.copy(vatId = value) }

    fun onTaxNumberChange(value: String) = updateForm { it.copy(taxNumber = value) }

    fun onStreetChange(value: String) = updateForm { it.copy(street = value) }

    fun onZipChange(value: String) = updateForm { it.copy(zip = value) }

    fun onCityChange(value: String) = updateForm { it.copy(city = value) }

    fun onCountryChange(value: String) = updateForm { it.copy(country = value) }

    fun onEmailChange(value: String) = updateForm { it.copy(email = value) }

    fun onPhoneChange(value: String) = updateForm { it.copy(phone = value) }

    fun onCreditorNoChange(value: String) = updateForm { it.copy(creditorNo = value) }

    fun onDefaultAccountNoChange(value: String) = updateForm { it.copy(defaultAccountNo = value) }

    fun onDefaultBuCodeChange(value: String) = updateForm { it.copy(defaultBuCode = value) }

    fun onDefaultCostCenterChange(value: String) = updateForm { it.copy(defaultCostCenter = value) }

    fun onPaymentTermsChange(value: String) = updateForm { it.copy(paymentTermsDays = value) }

    fun onNotesChange(value: String) = updateForm { it.copy(notes = value) }

    fun onActiveChange(value: Boolean) = updateForm { it.copy(isActive = value) }

    private fun updateForm(mutate: (SupplierForm) -> SupplierForm) {
        val updated = mutate(state.form)
        state =
            state.copy(
                form = updated,
                nameError = state.nameError.takeIf { updated.name == state.form.name },
                emailError = state.emailError.takeIf { updated.email == state.form.email },
            )
    }

    /**
     * Validates first and **returns without dispatching** when the name is blank or the e-mail is
     * malformed (the shared `EmailFormat`, which the server's `SupplierDto.validate()` also runs): the
     * request is a full replace, so sending an invalid form would be a destructive write.
     */
    fun save() {
        val supplierId = state.id ?: return
        val form = state.form
        val nameError = AdminStringKeys.SupplierErrorNameRequired.takeIf { form.name.isBlank() }
        val emailError = AdminStringKeys.PartnerEmailInvalid.takeIf { !EmailFormat.isAcceptable(form.email) }
        if (nameError != null || emailError != null) {
            log.w { "supplier $supplierId save blocked: name blank=${nameError != null} email invalid=${emailError != null}" }
            state = state.copy(nameError = nameError, emailError = emailError, validationAttempt = state.validationAttempt + 1)
            return
        }
        state = state.copy(isSaving = true, nameError = null, emailError = null, error = null)
        viewModelScope.launch { dispatchSave(supplierId, form) }
    }

    /**
     * A `try/catch` rather than [runCatchingApi] because a 409 carrying
     * [PosErrorCode.SUPPLIER_VAT_ID_TAKEN] must be surfaced as its own actionable message — the
     * operator can go and find the creditor that already owns the VAT ID — instead of the generic
     * server envelope.
     */
    private suspend fun dispatchSave(
        supplierId: Long,
        form: SupplierForm,
    ) {
        try {
            val updated = api.update(supplierId, form.toUpdateRequest())
            log.d { "supplier $supplierId saved" }
            onSaved(updated, form)
        } catch (e: PosApiException) {
            if (e.code == PosErrorCode.SUPPLIER_VAT_ID_TAKEN) {
                log.w { "supplier $supplierId save rejected: VAT ID already belongs to another creditor" }
                failSave(e.toVatIdTakenError())
            } else {
                log.e { "supplier $supplierId save failed: $e" }
                failSave(e.toUiError())
            }
        } catch (e: NetworkApiException) {
            log.e(e) { "supplier $supplierId save failed: transport error" }
            failSave(e.toUiError())
        }
    }

    private fun onSaved(
        updated: SupplierDto.ResponseDto,
        savedForm: SupplierForm,
    ) {
        state =
            state.copy(
                isSaving = false,
                pristine = savedForm,
                isProvisional = updated.isProvisional,
            )
    }

    private fun failSave(uiError: UiError) {
        state = state.copy(isSaving = false, error = uiError)
    }

    fun clearError() {
        state = state.copy(error = null)
    }
}

private fun PosApiException.toVatIdTakenError() =
    UiError.FromServer(
        code = code,
        traceId = traceId,
        titleKey = CommonKeys.unknownErrorTitle,
        messageKey = AdminStringKeys.SupplierErrorVatIdTaken,
        httpStatus = httpStatus,
        fieldErrors = fieldErrors,
        rawDetail = message,
    )
