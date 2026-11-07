package org.example.project.admin.features.salesinvoices.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.SalesInvoiceApiClient
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

/**
 * Ausgangsrechnungen — list, open, issue, cancel.
 *
 * Every mutation **re-reads from the server** rather than patching the row locally. The server
 * decides the §14 number, the status and the archived document id; a client that guessed them would
 * disagree with the PDF an operator is about to hand a customer.
 */
class SalesInvoicesViewModel(
    private val api: SalesInvoiceApiClient,
) : ViewModel() {
    private val log = Logger.withTag("salesInvoices")

    var state by mutableStateOf(SalesInvoicesState())
        private set

    fun clearError() {
        state = state.copy(error = null)
    }

    fun selectBucket(bucket: SalesInvoiceBucket) {
        if (bucket == state.bucket) return
        // The selection is dropped: it belongs to the bucket being left, and keeping it would leave
        // the detail panel showing a row the list no longer contains.
        state = state.copy(bucket = bucket, selected = null)
        load()
    }

    fun load() {
        val bucket = state.bucket
        state = state.copy(isLoading = true, error = null)
        loadCounts()
        viewModelScope.launch {
            runCatchingApi { api.list(status = bucket.status, type = bucket.typeCode, open = bucket.open, overdue = bucket.overdue) }
                .onSuccess { response ->
                    log.d { "sales invoices loaded: bucket=$bucket count=${response.items.size} total=${response.total}" }
                    state = state.copy(isLoading = false, items = response.items, total = response.total)
                }.onError(retry = { load() }) { uiError ->
                    log.e { "sales invoice list failed: $uiError" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }

    /**
     * Reads every bucket's total (a page of one each) for the Belegfluss strip. A failure leaves the
     * previous counts and is only logged: the strip is orientation, not something to raise an error
     * over the list the operator came for.
     */
    private fun loadCounts() {
        viewModelScope.launch {
            runCatchingApi {
                coroutineScope {
                    SalesInvoiceBucket.entries
                        .map { bucket ->
                            async {
                                bucket to
                                    api.list(status = bucket.status, type = bucket.typeCode, open = bucket.open, overdue = bucket.overdue, limit = 1).total
                            }
                        }.awaitAll()
                        .toMap()
                }
            }.onSuccess { counts -> state = state.copy(bucketCounts = counts) }
                .onError { uiError -> log.w { "sales invoice counts failed: $uiError" } }
        }
    }

    fun select(id: Long) {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi { api.get(id) }
                .onSuccess { state = state.copy(isLoading = false, selected = it) }
                .onError(retry = { select(id) }) { uiError ->
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }

    fun clearSelection() {
        state = state.copy(selected = null)
    }

    /** Irreversible. The UI confirms first; the server refuses a second attempt with a 409. */
    fun issueSelected() {
        val id = state.selected?.id ?: return
        state = state.copy(isMutating = true, error = null)
        viewModelScope.launch {
            runCatchingApi { api.issue(id) }
                .onSuccess { issued ->
                    log.d { "issued invoice $id as ${issued.invoiceNumber}" }
                    state = state.copy(isMutating = false, selected = issued)
                    load()
                }.onError(retry = { issueSelected() }) { uiError ->
                    log.e { "issue failed for $id: $uiError" }
                    state = state.copy(isMutating = false, error = uiError)
                }
        }
    }

    /**
     * Cancels by issuing a 381 Gutschrift, which the server returns.
     *
     * The Gutschrift becomes the selection, not the cancelled original: it is the document that was
     * just created and the one an operator will want to print.
     */
    fun cancelSelected(reason: String) {
        val id = state.selected?.id ?: return
        // FO-65: mandatory, at most 60 characters — the server refuses too (project rule 8).
        if (!SalesInvoiceDto.CancelRequestDto.isValidReason(reason)) return
        state = state.copy(isMutating = true, error = null)
        viewModelScope.launch {
            runCatchingApi { api.cancel(id, SalesInvoiceDto.CancelRequestDto(reason = reason.trim())) }
                .onSuccess { storno ->
                    log.d { "cancelled $id via Gutschrift ${storno.invoiceNumber}" }
                    state = state.copy(isMutating = false, selected = storno)
                    load()
                }.onError(retry = { cancelSelected(reason) }) { uiError ->
                    log.e { "cancel failed for $id: $uiError" }
                    state = state.copy(isMutating = false, error = uiError)
                }
        }
    }
}
