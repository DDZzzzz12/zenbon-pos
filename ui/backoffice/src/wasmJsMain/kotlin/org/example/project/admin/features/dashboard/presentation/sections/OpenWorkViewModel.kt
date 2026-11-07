package org.example.project.admin.features.dashboard.presentation.sections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.AllocationApiClient
import org.example.project.core.data.remote.InvoiceListApiClient
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

/** Counts only — one row is fetched so the response carries its bucket counts, and is discarded. */
private const val COUNT_ONLY_LIMIT = 1

/**
 * What is waiting in Rechnungen and Zuordnung.
 *
 * Two requests fill four tiles: the invoice list answers with `bucketCounts` for every bucket
 * whichever one it was asked for, and the allocation queue answers with its `total`. Asking for one
 * row each is what makes both responses cheap — the rows are never read.
 */
class OpenWorkViewModel(
    private val invoiceListApiClient: InvoiceListApiClient,
    private val allocationApiClient: AllocationApiClient,
) : ViewModel() {
    private val log = Logger.withTag("dashboard-open-work")

    var state by mutableStateOf(OpenWorkState())
        private set

    fun refresh() {
        state = state.copy(isLoading = true, failed = false)
        viewModelScope.launch {
            runCatchingApi {
                coroutineScope {
                    val invoices =
                        async {
                            invoiceListApiClient.list(
                                bucket = InvoiceListDto.Bucket.ZU_ERFASSEN,
                                limit = COUNT_ONLY_LIMIT,
                            )
                        }
                    val queue =
                        async {
                            allocationApiClient.queue(
                                filter = AllocationDto.QueueFilter.NICHT_ZUGEORDNET,
                                limit = COUNT_ONLY_LIMIT,
                            )
                        }
                    invoices.await() to queue.await()
                }
            }.onSuccess { (invoices, queue) ->
                val counts = invoices.bucketCounts
                state =
                    OpenWorkState(
                        isLoading = false,
                        failed = false,
                        toCapture = counts[InvoiceListDto.Bucket.ZU_ERFASSEN.name] ?: 0L,
                        toReview = counts[InvoiceListDto.Bucket.ZU_PRUEFEN.name] ?: 0L,
                        overdue = counts[InvoiceListDto.Bucket.UEBERFAELLIG.name] ?: 0L,
                        unassignedPayments = queue.total,
                    )
            }.onError { uiError ->
                log.w { "open work load failed: $uiError" }
                state = OpenWorkState(isLoading = false, failed = true)
            }
        }
    }
}
