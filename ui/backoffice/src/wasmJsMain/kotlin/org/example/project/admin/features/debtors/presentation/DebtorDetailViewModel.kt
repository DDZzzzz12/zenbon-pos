package org.example.project.admin.features.debtors.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.remote.DebtorApiClient
import org.example.project.core.domain.dto.debtor.DebtorDto
import org.example.project.core.domain.enums.salesinvoice.EInvoiceFormatPreference
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys

/**
 * Drives the Debitor detail screen: loads one debtor (or starts an empty create form), tracks the
 * editable [DebtorForm] against its pristine snapshot, saves, deactivates and deletes.
 *
 * [save] validates before dispatching — a blank name never reaches the server, the fields at fault are
 * marked and the first is focused — and re-pins
 * `pristine` only on success, so a failed save keeps the screen dirty.
 */
class DebtorDetailViewModel(
    private val api: DebtorApiClient,
) : ViewModel() {
    private val log = Logger.withTag("debtorDetail")

    var state by mutableStateOf(DebtorDetailState())
        private set

    private var loadJob: Job? = null

    /**
     * Drops everything this view model holds. The admin view models share one store owner and outlive
     * a logout, so without this a dirty draft would be shown to the next user who signs in.
     */
    fun reset() {
        loadJob?.cancel()
        loadJob = null
        state = DebtorDetailState()
    }

    /**
     * A null [debtorId] starts the create form rather than fetching.
     *
     * [keepDraft] is for a pane that merely **remounted** (a list reload, a phone Back and reselect,
     * a resize across the phone width): it skips the fetch while this view model already holds unsaved
     * edits for the same debtor. Selecting a *different* debtor still discards, and clears the old
     * form at once so it can never be saved over the new record while the new one loads.
     */
    fun load(
        debtorId: Long?,
        keepDraft: Boolean = false,
    ) {
        if (keepDraft && debtorId != null && state.holdsDraftFor(debtorId)) return
        loadJob?.cancel()
        if (debtorId == null) {
            state = DebtorDetailState()
            return
        }
        val base =
            if (state.id == debtorId) state else DebtorDetailState(savedRevision = state.savedRevision)
        state = base.copy(isLoading = true, error = null)
        loadJob =
            viewModelScope.launch {
                runCatchingApi { api.get(debtorId) }
                    .onSuccess { dto -> state = state.fromDto(dto).copy(isLoading = false) }
                    .onError(retry = { load(debtorId) }) { uiError ->
                        log.e { "debtor $debtorId load failed: $uiError" }
                        state = state.copy(isLoading = false, error = uiError)
                    }
            }
    }

    /**
     * Drops unsaved edits: the form goes back to what was last loaded or saved. The leave guard
     * calls this on Discard, so a later remount with `keepDraft = true` finds a clean state and
     * cannot resurrect the discarded text.
     */
    fun discardDraft() {
        state = state.copy(form = state.pristine, fieldErrors = emptyMap())
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

    fun onDebtorNoChange(value: String) = updateForm { it.copy(debtorNo = value) }

    fun onPaymentTermsChange(value: String) = updateForm { it.copy(paymentTermsDays = value) }

    fun onRevenueAccountChange(value: String) = updateForm { it.copy(revenueAccountNo = value) }

    fun onBuCodeChange(value: String) = updateForm { it.copy(buCode = value) }

    fun onNotesChange(value: String) = updateForm { it.copy(notes = value) }

    fun onActiveChange(value: Boolean) = updateForm { it.copy(isActive = value) }

    fun onEinvoiceFormatChange(value: EInvoiceFormatPreference) = updateForm { it.copy(einvoiceFormat = value) }

    fun clearError() {
        state = state.copy(error = null)
    }

    /** The create screen has shown the confirmation and navigated; the one-shot is spent. */
    fun consumeCreated() {
        state = state.copy(createdDebtorId = null)
    }

    /** Creates when [DebtorDetailState.id] is null, replaces otherwise. */
    fun save() {
        if (state.isLoading || state.isSaving) return
        val errors = validateDebtorForm(state.form)
        if (errors.isNotEmpty()) {
            log.d { "debtor save refused locally: ${errors.keys}" }
            state = state.copy(fieldErrors = errors, validationAttempt = state.validationAttempt + 1)
            return
        }
        val id = state.id
        state = state.copy(isSaving = true, fieldErrors = emptyMap(), error = null)
        viewModelScope.launch {
            runCatchingApi {
                if (id == null) {
                    api.create(state.form.toCreateRequest())
                } else {
                    api.update(id, state.form.toUpdateRequest())
                }
            }.onSuccess { dto ->
                log.d { "debtor ${dto.id} saved" }
                // The right-hand side reads the OLD state, so the counter carries across the
                // rebuild `fromDto` does rather than resetting to its default.
                state =
                    state.fromDto(dto).copy(
                        isSaving = false,
                        savedRevision = state.savedRevision + 1,
                        // NEW-20: a create announces itself so the screen can confirm it and leave
                        // the create route; an update stays where it is.
                        createdDebtorId = if (id == null) dto.id else null,
                    )
            }.onError { uiError ->
                val serverFields = serverFieldErrors(uiError)
                if (serverFields.isNotEmpty()) {
                    // The server's own check (the same EmailFormat) refused a field: mark it like a
                    // client-side refusal instead of raising a dialog over the form.
                    log.w { "debtor save refused by server: ${serverFields.keys}" }
                    state =
                        state.copy(
                            isSaving = false,
                            fieldErrors = serverFields,
                            validationAttempt = state.validationAttempt + 1,
                        )
                } else {
                    log.e { "debtor save failed: $uiError" }
                    state = state.copy(isSaving = false, error = uiError)
                }
            }
        }
    }

    /**
     * Removes a debtor nothing references.
     *
     * Refuses to even dispatch when an invoice points at it — the server answers 409 `DEBTOR_IN_USE`
     * anyway, and both levels is the project rule.
     */
    fun delete() {
        val id = state.id ?: return
        if (!state.canDelete) {
            log.w { "delete refused locally: debtor $id has ${state.salesInvoiceCount} invoice(s)" }
            return
        }
        state = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            runCatchingApi { api.delete(id) }
                .onSuccess { state = state.copy(isSaving = false, isDeleted = true) }
                .onError { uiError ->
                    log.e { "debtor $id delete failed: $uiError" }
                    state = state.copy(isSaving = false, error = uiError)
                }
        }
    }

    /** The 400's field errors this form can mark; anything else stays a dialog. */
    private fun serverFieldErrors(uiError: UiError): Map<DebtorFormField, StringKey> =
        (uiError as? UiError.FromServer)
            ?.fieldErrors
            .orEmpty()
            .mapNotNull { fieldError ->
                when (fieldError.field) {
                    DebtorDto.FIELD_EMAIL -> DebtorFormField.EMAIL to AdminStringKeys.PartnerEmailInvalid
                    DebtorDto.FIELD_NAME -> DebtorFormField.NAME to CommonKeys.fieldRequired
                    else -> null
                }
            }.toMap()

    private fun updateForm(transform: (DebtorForm) -> DebtorForm) {
        val updated = transform(state.form)
        state = state.copy(form = updated, fieldErrors = state.fieldErrors.clearedFor(state.form, updated))
    }

    private fun DebtorDetailState.fromDto(dto: DebtorDto.ResponseDto): DebtorDetailState {
        val form = DebtorForm.from(dto)
        return copy(
            id = dto.id,
            form = form,
            pristine = form,
            salesInvoiceCount = dto.salesInvoiceCount,
            fieldErrors = emptyMap(),
        )
    }
}
