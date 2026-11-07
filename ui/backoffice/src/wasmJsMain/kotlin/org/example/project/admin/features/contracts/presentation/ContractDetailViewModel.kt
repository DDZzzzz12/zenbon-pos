package org.example.project.admin.features.contracts.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.ContractApiClient
import org.example.project.core.data.remote.SupplierApiClient
import org.example.project.core.domain.dto.contract.ContractDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

/**
 * Drives one contract — the form and the instalment strip beside it.
 *
 * **A null contract id is the create form.** [load] seeds an empty form and makes no request at all
 * in that case, and [save] posts rather than puts on the same null, so one screen serves both.
 */
class ContractDetailViewModel(
    private val api: ContractApiClient,
    private val supplierApi: SupplierApiClient,
) : ViewModel() {
    private val log = Logger.withTag("contractDetail")

    var state by mutableStateOf(ContractDetailState())
        private set

    fun clearError() {
        state = state.copy(error = null)
    }

    /** Drops unsaved edits: the form goes back to what was last loaded or saved (empty for a create). */
    fun discardDraft() {
        state = state.copy(form = state.pristine, errors = ContractFormErrors())
    }

    /** [contractId] null seeds the create form; there is nothing to fetch and nothing due yet. */
    fun load(contractId: Long?) {
        if (contractId == null) {
            log.d { "create form opened" }
            state = ContractDetailState()
            loadSuppliers()
            return
        }
        state = state.copy(isLoading = true, error = null)
        loadSuppliers()
        viewModelScope.launch {
            runCatchingApi { api.get(contractId) }
                .onSuccess { dto ->
                    log.d { "contract $contractId loaded" }
                    onLoaded(dto)
                    loadInstalments(contractId)
                    loadAlerts(contractId)
                }.onError(retry = { load(contractId) }) { uiError ->
                    log.e { "contract $contractId load failed: $uiError" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }

    private fun onLoaded(dto: ContractDto.ResponseDto) {
        val form = ContractForm.from(dto)
        state =
            state.copy(
                id = dto.id,
                isLoading = false,
                form = form,
                pristine = form,
                currency = dto.currency,
                loadedSupplierName = dto.supplierName,
                selectedInstalmentId = null,
                alerts = emptyList(),
                errors = ContractFormErrors(),
            )
    }

    /**
     * The supplier picker's options. A failure leaves the list empty: the picker then shows the
     * stored id, and the form still saves — the name is a convenience, not part of the contract.
     */
    private fun loadSuppliers() {
        viewModelScope.launch {
            runCatchingApi { supplierApi.list() }
                .onSuccess { response -> state = state.copy(suppliers = response.items) }
                .onError { uiError -> log.w { "suppliers for picker failed: $uiError" } }
        }
    }

    private suspend fun loadInstalments(contractId: Long) {
        runCatchingApi { api.instalments(contractId) }
            .onSuccess { response ->
                log.d { "contract $contractId instalments: ${response.items.size}" }
                state = state.copy(instalments = response.items)
            }.onError { uiError ->
                log.e { "contract $contractId instalments failed: $uiError" }
                state = state.copy(error = uiError)
            }
    }

    /**
     * The endpoint answers for every active contract, so this one's are filtered out here rather
     * than asked for separately — there is no per-contract alerts route, and inventing one would be
     * a second place the same derivation could drift.
     *
     * A failure leaves the loaded contract and its strip alone; only the alert block goes missing.
     */
    private suspend fun loadAlerts(contractId: Long) {
        runCatchingApi { api.alerts() }
            .onSuccess { response ->
                val mine = response.items.filter { it.contractId == contractId }
                log.d { "contract $contractId alerts: ${mine.size}" }
                state = state.copy(alerts = mine)
            }.onError { uiError ->
                log.e { "contract $contractId alerts failed: $uiError" }
                state = state.copy(error = uiError)
            }
    }

    /** Tapping the selected cell again clears it, so the strip can be read without a panel below it. */
    fun selectInstalment(instalmentId: Long) {
        state =
            state.copy(
                selectedInstalmentId = instalmentId.takeIf { it != state.selectedInstalmentId },
            )
    }

    fun onNameChange(value: String) = updateForm { it.copy(name = value) }

    fun onDescriptionChange(value: String) = updateForm { it.copy(description = value) }

    fun onSupplierIdChange(value: String) = updateForm { it.copy(supplierId = value) }

    fun onExpectedAmountChange(value: String) = updateForm { it.copy(expectedAmount = value) }

    fun onTolerancePctChange(value: String) = updateForm { it.copy(tolerancePct = value) }

    fun onToleranceAbsChange(value: String) = updateForm { it.copy(toleranceAbs = value) }

    fun onIntervalChange(value: String) = updateForm { it.copy(interval = value) }

    fun onDueDayChange(value: String) = updateForm { it.copy(dueDay = value) }

    fun onDueWeekdayChange(value: String) = updateForm { it.copy(dueWeekday = value) }

    fun onStartDateChange(value: String) = updateForm { it.copy(startDate = value) }

    fun onEndDateChange(value: String) = updateForm { it.copy(endDate = value) }

    fun onNoticePeriodChange(value: String) = updateForm { it.copy(noticePeriodDays = value) }

    fun onExpectsInvoiceChange(value: Boolean) = updateForm { it.copy(expectsInvoice = value) }

    fun onDefaultAccountNoChange(value: String) = updateForm { it.copy(defaultAccountNo = value) }

    fun onDefaultBuCodeChange(value: String) = updateForm { it.copy(defaultBuCode = value) }

    fun onDefaultCostCenterChange(value: String) = updateForm { it.copy(defaultCostCenter = value) }

    fun onActiveChange(value: Boolean) = updateForm { it.copy(isActive = value) }

    private fun updateForm(mutate: (ContractForm) -> ContractForm) {
        val updated = mutate(state.form)
        state = state.copy(form = updated, errors = state.errors.recheckedFor(updated))
    }

    /**
     * Validates first and **returns without dispatching** when anything fails: the update is a full
     * replace, so sending an invalid form would be a destructive write, and a create would only
     * earn the same 400 the client can name here.
     */
    fun save() {
        val form = state.form
        val errors = ContractFormErrors.of(form)
        if (!errors.isEmpty) {
            log.w { "contract save blocked: $errors" }
            state = state.copy(errors = errors, validationAttempt = state.validationAttempt + 1)
            return
        }
        state = state.copy(isSaving = true, errors = ContractFormErrors(), error = null)
        val contractId = state.id
        viewModelScope.launch {
            runCatchingApi {
                if (contractId == null) api.create(form.toCreateRequest()) else api.update(contractId, form.toUpdateRequest())
            }.onSuccess { dto ->
                log.d { "contract ${dto.id} saved" }
                state = state.copy(id = dto.id, isSaving = false, pristine = form)
            }.onError { uiError ->
                log.e { "contract save failed: $uiError" }
                state = state.copy(isSaving = false, error = uiError)
            }
        }
    }
}
