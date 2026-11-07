package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.PaymentTypeApiClient
import org.example.project.core.domain.dto.payment.PaymentTypeDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.features.settings.presentation.state.PaymentTypeManagementState

/**
 * ViewModel for the adminApp Zahlungsarten section — payment-type CRUD over HTTP.
 *
 * Distinct from composeApp's `PaymentTypeViewModel`, which stays in composeApp because it depends on
 * `SyncService` to reconcile a till's local SQLite cache. A web admin has no cache, so this talks to
 * [PaymentTypeApiClient] directly. It produces the same [PaymentTypeManagementState] that shared-ui's
 * `PaymentTypeManagementContent` already renders for composeApp, so the two clients cannot drift on
 * payment-type behavior.
 *
 * A create/update sets [PaymentTypeManagementState.isSaving] until it answers; the dialog's Save shows
 * progress meanwhile and a second press is ignored here, so a double click cannot create two types.
 *
 * Inactive types are included: an operator managing payment methods needs to see the ones they
 * previously deactivated, otherwise a deactivated type is invisible and un-reactivatable.
 */
class PaymentTypeViewModel(
    private val paymentTypeApiClient: PaymentTypeApiClient,
) : ViewModel() {
    private val log = Logger.withTag("payment-types")

    var state by mutableStateOf(PaymentTypeManagementState())
        private set

    /** Driven by the section on entry — the VM is retained app-wide, so `init` would go stale. */
    fun refresh() {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi { paymentTypeApiClient.getAllPaymentTypes(includeInactive = true) }
                .onSuccess { response ->
                    state = state.copy(paymentTypes = response.paymentTypes, isLoading = false)
                }.onError(retry = ::refresh) { uiError ->
                    log.e { "load payment types failed" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    // ----- Dialog plumbing -----

    fun showAddDialog() {
        state = state.copy(showAddDialog = true, editingPaymentType = null)
    }

    fun showEditDialog(paymentType: PaymentTypeDto.PaymentTypeResponseDto) {
        state = state.copy(showAddDialog = true, editingPaymentType = paymentType)
    }

    fun hideDialog() {
        state = state.copy(showAddDialog = false, editingPaymentType = null)
    }

    // ----- Mutations -----

    fun create(
        name: String,
        en: String?,
        ch: String?,
        isCash: Boolean,
        createsReceivable: Boolean,
    ) {
        if (state.isSaving) return
        log.i { "creating payment type isCash=$isCash createsReceivable=$createsReceivable" }
        state = state.copy(isSaving = true)
        viewModelScope.launch {
            runCatchingApi {
                paymentTypeApiClient.createPaymentType(
                    PaymentTypeDto.CreatePaymentTypeRequestDto(
                        name = name,
                        en = en,
                        ch = ch,
                        isCash = isCash,
                        createsReceivable = createsReceivable,
                    ),
                )
            }.onSuccess {
                state = state.copy(isSaving = false)
                hideDialog()
                refresh()
            }.onError { uiError ->
                log.e { "create payment type failed" }
                state = state.copy(isSaving = false, error = uiError)
            }
        }
    }

    fun update(
        id: Long,
        name: String,
        en: String?,
        ch: String?,
        isCash: Boolean,
        createsReceivable: Boolean,
    ) {
        if (state.isSaving) return
        log.i { "updating payment type id=$id" }
        state = state.copy(isSaving = true)
        viewModelScope.launch {
            runCatchingApi {
                paymentTypeApiClient.updatePaymentType(
                    id,
                    PaymentTypeDto.UpdatePaymentTypeRequestDto(
                        name = name,
                        en = en,
                        ch = ch,
                        isCash = isCash,
                        createsReceivable = createsReceivable,
                    ),
                )
            }.onSuccess {
                state = state.copy(isSaving = false)
                hideDialog()
                refresh()
            }.onError { uiError ->
                log.e { "update payment type failed id=$id" }
                state = state.copy(isSaving = false, error = uiError)
            }
        }
    }

    fun delete(id: Long) {
        log.i { "deleting payment type id=$id" }
        viewModelScope.launch {
            runCatchingApi { paymentTypeApiClient.deletePaymentType(id) }
                .onSuccess { refresh() }
                .onError { uiError ->
                    log.e { "delete payment type failed id=$id" }
                    state = state.copy(error = uiError)
                }
        }
    }
}
