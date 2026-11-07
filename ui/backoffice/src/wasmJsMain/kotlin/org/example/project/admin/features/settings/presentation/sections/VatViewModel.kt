package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.VatApiClient
import org.example.project.core.domain.dto.setup.VatDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError

/** State for the adminApp MwSt-Sätze section. */
data class VatRatesState(
    val rates: List<VatDto.VatResponseDto> = emptyList(),
    val isLoading: Boolean = false,
    val showDialog: Boolean = false,
    val editing: VatDto.VatResponseDto? = null,
    /** A create/update from the dialog is in flight: its Save shows progress and ignores presses. */
    val isSaving: Boolean = false,
    val error: UiError? = null,
)

/**
 * ViewModel for the adminApp MwSt-Sätze section — VAT-rate CRUD over HTTP.
 *
 * There is no composeApp counterpart to reuse: composeApp drives VAT through its
 * `BusinessSettingsViewModel`-era code and a free-text rate field, which deliberately
 * stays put. adminApp instead pairs this with `VatRatePicker`, so only a statutorily current rate can
 * be chosen — see that component for why the distinction matters.
 *
 * `vatId` is the caller-supplied primary key (a `String`), not server-generated, so create and update
 * are genuinely different operations here rather than one upsert.
 */
class VatViewModel(
    private val vatApiClient: VatApiClient,
) : ViewModel() {
    private val log = Logger.withTag("vat-rates")

    var state by mutableStateOf(VatRatesState())
        private set

    /** Driven by the section on entry — the VM is retained app-wide, so `init` would go stale. */
    fun refresh() {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi { vatApiClient.getAllVatRates() }
                .onSuccess { rates -> state = state.copy(rates = rates, isLoading = false) }
                .onError(retry = ::refresh) { uiError ->
                    log.e { "load VAT rates failed" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    fun showAddDialog() {
        state = state.copy(showDialog = true, editing = null)
    }

    fun showEditDialog(rate: VatDto.VatResponseDto) {
        state = state.copy(showDialog = true, editing = rate)
    }

    fun hideDialog() {
        state = state.copy(showDialog = false, editing = null)
    }

    fun create(
        vatId: String,
        vatClass: String,
        vatRate: Double,
    ) {
        if (state.isSaving) return
        log.i { "creating VAT rate vatId=$vatId rate=$vatRate" }
        state = state.copy(isSaving = true)
        viewModelScope.launch {
            runCatchingApi {
                vatApiClient.createVatRate(
                    VatDto.CreateVatRequestDto(vatId = vatId, vatClass = vatClass, vatRate = vatRate),
                )
            }.onSuccess {
                state = state.copy(isSaving = false)
                hideDialog()
                refresh()
            }.onError { uiError ->
                log.e { "create VAT rate failed vatId=$vatId" }
                state = state.copy(isSaving = false, error = uiError)
            }
        }
    }

    fun update(
        vatId: String,
        vatClass: String,
        vatRate: Double,
    ) {
        if (state.isSaving) return
        log.i { "updating VAT rate vatId=$vatId rate=$vatRate" }
        state = state.copy(isSaving = true)
        viewModelScope.launch {
            runCatchingApi {
                vatApiClient.updateVatRate(
                    vatId,
                    VatDto.UpdateVatRequestDto(vatClass = vatClass, vatRate = vatRate),
                )
            }.onSuccess {
                state = state.copy(isSaving = false)
                hideDialog()
                refresh()
            }.onError { uiError ->
                log.e { "update VAT rate failed vatId=$vatId" }
                state = state.copy(isSaving = false, error = uiError)
            }
        }
    }

    fun delete(vatId: String) {
        log.i { "deleting VAT rate vatId=$vatId" }
        viewModelScope.launch {
            runCatchingApi { vatApiClient.deleteVatRate(vatId) }
                .onSuccess { refresh() }
                .onError { uiError ->
                    log.e { "delete VAT rate failed vatId=$vatId" }
                    state = state.copy(error = uiError)
                }
        }
    }
}
