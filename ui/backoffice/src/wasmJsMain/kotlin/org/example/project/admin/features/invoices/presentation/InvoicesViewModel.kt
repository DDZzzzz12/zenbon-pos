package org.example.project.admin.features.invoices.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.admin.features.finance.presentation.BookkeepingStartController
import org.example.project.core.data.remote.BookkeepingStartApiClient
import org.example.project.core.data.remote.InvoiceListApiClient
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

/**
 * Drives the Rechnungen list: its capture buckets, its payment buckets and the confirmation action.
 *
 * Both the bucket switch and [markReviewed] end in [load] rather than adjusting the loaded list.
 * The server decides what still belongs in a bucket, so a locally-mutated list drifts from it —
 * and the counts on the chips would go stale the moment a row moved.
 *
 * It also hosts the Buchungsbeginn banner. Rechnungen sits above Zuordnung in the sidebar, so it is
 * the likelier first screen, and the cut-off decides which invoices count as *Offen* at all. The
 * banner's own behaviour lives in [BookkeepingStartController], which Zuordnung hosts too — neither
 * screen's ViewModel knows about the other, and the server's durable acknowledgement means whichever
 * shows it first, it stays up until a manager confirms the date.
 */
class InvoicesViewModel(
    private val api: InvoiceListApiClient,
    bookkeepingApi: BookkeepingStartApiClient,
) : ViewModel() {
    private val log = Logger.withTag("invoices")

    var state by mutableStateOf(InvoicesState())
        private set

    val bookkeeping =
        BookkeepingStartController(
            api = bookkeepingApi,
            scope = viewModelScope,
            onError = { uiError -> state = state.copy(error = uiError) },
        )

    fun clearError() {
        state = state.copy(error = null)
    }

    fun load() {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi { api.list(bucket = state.bucket) }
                .onSuccess { response ->
                    log.d { "invoices loaded: bucket=${state.bucket} count=${response.items.size} total=${response.total}" }
                    state =
                        state.copy(
                            isLoading = false,
                            items = response.items,
                            error = null,
                            bucketCounts = response.bucketCounts,
                            bucketSums = response.bucketSums,
                            // Every row on a page carries the same shop currency; the first is as
                            // good a source as any and beats a hardcoded "EUR" in the tiles.
                            currency = response.items.firstOrNull()?.currency ?: state.currency,
                        )
                    redirectAwayFromEmptyOpeningBucket()
                }.onError(retry = { load() }) { uiError ->
                    log.e { "invoices load failed: $uiError" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
        bookkeeping.load()
    }

    /**
     * Writes the new cut-off, then re-reads the list.
     *
     * The cut-off decides which invoices count as open, so both the rows and the bucket counts can
     * have moved; keeping the loaded ones would show payment state the server no longer agrees with.
     */
    fun saveBookkeepingStart(date: String) = bookkeeping.save(date) { load() }

    /** Returns without dispatching when the bucket is already shown — tapping the active chip is not a request. */
    fun selectBucket(bucket: InvoiceListDto.Bucket) {
        if (bucket == state.bucket) return
        // Marks the redirect spent: from here on an empty bucket is the operator's own choice.
        state = state.copy(bucket = bucket, hasAutoSelectedBucket = true)
        load()
    }

    /**
     * Opens a bucket the caller named — a dashboard tile, not the operator.
     *
     * Sets [InvoicesState.hasAutoSelectedBucket] so [redirectAwayFromEmptyOpeningBucket] does not
     * fire: a tile that sends the operator to "Überfällig" must land there, and bouncing them
     * straight back out of a bucket they were pointed at is worse than showing it empty.
     *
     * Unlike [selectBucket] there is no already-showing guard — the caller is stating an intent on
     * arrival, not toggling a chip.
     */
    fun openBucket(bucket: InvoiceListDto.Bucket) {
        state = state.copy(bucket = bucket, hasAutoSelectedBucket = true)
        load()
    }

    /**
     * Moves off the opening bucket when it is empty and another one holds work.
     *
     * The screen opens on `ZU_ERFASSEN` because that is where a newly scanned Beleg lands, but on a
     * shop that is up to date it is empty by definition — so the landing view showed "nothing to
     * capture" while 112 invoices waited in "Zu prüfen". The first bucket in the enum with a
     * non-zero count is the earliest stage of the workflow that actually has something in it.
     */
    private fun redirectAwayFromEmptyOpeningBucket() {
        if (state.hasAutoSelectedBucket || state.items.isNotEmpty()) return
        val target =
            InvoiceListDto.Bucket.entries.firstOrNull { bucket ->
                bucket != state.bucket && (state.bucketCounts[bucket.name] ?: 0L) > 0L
            } ?: return
        state = state.copy(bucket = target, hasAutoSelectedBucket = true)
        load()
    }

    fun markReviewed(invoiceId: Long) {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi { api.markReviewed(invoiceId) }
                .onSuccess {
                    log.d { "invoice $invoiceId marked reviewed" }
                    load()
                }.onError { uiError ->
                    log.e { "invoice $invoiceId review failed: $uiError" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }
}
