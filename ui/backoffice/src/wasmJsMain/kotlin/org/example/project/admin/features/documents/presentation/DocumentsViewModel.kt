package org.example.project.admin.features.documents.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.data.remote.DocumentManagementApiClient
import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.features.reports.presentation.ReportsGranularity
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val PAGE_SIZE = 50
private const val SEARCH_DEBOUNCE_MS = 300L

/**
 * Drives the document archive. Quick filters (search term, period, type, include-inactive) apply
 * straight to `applied`; the seven advanced fields and the status chips edit `draft` and only reach
 * a request on [applyDraft]. Paging appends, so the list keeps what is already on screen.
 *
 * [search] and [loadMore] hold **separate** job handles ([searchJob] / [loadMoreJob]) rather than
 * sharing one, because they are not interchangeable: a full [search] replacing `applied` and the
 * result list may correctly pre-empt an in-flight [loadMore] (its page would belong to a filter that
 * no longer applies), but the reverse is never correct — a paging request must not be able to kill a
 * running search, or the search's own result is lost and `state.items.size` (the paging offset) is
 * read against a list the search hasn't replaced yet, producing an offset for the *new* filter spliced
 * onto the *old* filter's rows. [loadMore] additionally refuses to start at all while [DocumentsState.isLoading]
 * is set, so it can never race a search in the first place.
 *
 * Both still funnel through the monotonic [requestSeq] stamped in [fetch]: cancellation narrows the
 * window but does not close it (a response can already be past the cancellation check when a newer
 * request starts), so the sequence check is the actual correctness guarantee — a resolved response
 * whose `seq` is no longer current is discarded rather than applied.
 */
class DocumentsViewModel(
    private val api: DocumentManagementApiClient,
) : ViewModel() {
    private val log = Logger.withTag("documents")

    @OptIn(ExperimentalTime::class)
    private fun today(): LocalDate =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date

    var state by mutableStateOf(DocumentsState.initial(today()))
        private set

    /** The debounce-delay timer started by [updateSearchTerm]; deliberately its own field — see below. */
    private var debounceJob: Job? = null
    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private var requestSeq = 0

    fun clearError() {
        state = state.copy(error = null)
    }

    fun toggleAdvanced() {
        // Reopening starts from what is applied, so an abandoned draft never lingers.
        state =
            state.copy(
                advancedExpanded = !state.advancedExpanded,
                draft = if (state.advancedExpanded) state.draft else state.applied,
            )
    }

    fun updateDraft(
        field: DocumentSearchFilter.AdvancedField,
        value: String,
    ) {
        state = state.copy(draft = state.draft.withAdvanced(field, value))
    }

    /** A status chip in the advanced panel — edits the draft like the text fields do. */
    fun toggleDraftStatus(status: ProcessingStatus) {
        state = state.copy(draft = state.draft.withStatusToggled(status))
    }

    /**
     * Applies the advanced panel's seven fields onto the CURRENT [DocumentsState.applied], not a
     * wholesale replace by [DocumentsState.draft]. `draft` is a whole-filter snapshot taken when the
     * panel opened ([toggleAdvanced]), but the quick filters (search term, type, period,
     * include-inactive) stay live and editable while the panel is open — replacing `applied` with
     * that stale snapshot would silently revert any quick filter the user changed in the meantime.
     */
    fun applyDraft() {
        val merged =
            DocumentSearchFilter.AdvancedField.entries.fold(
                state.applied.copy(processingStatuses = state.draft.processingStatuses),
            ) { acc, field ->
                acc.withAdvanced(field, state.draft.fieldValue(field))
            }
        state = state.copy(applied = merged, draft = merged, advancedExpanded = false)
        search()
    }

    fun resetAdvanced() {
        val cleared = state.applied.clearAllAdvanced()
        state = state.copy(applied = cleared, draft = cleared)
        search()
    }

    fun clearAdvancedField(field: DocumentSearchFilter.AdvancedField) {
        val cleared = state.applied.clear(field)
        state = state.copy(applied = cleared, draft = cleared)
        search()
    }

    /** The chip row's remove action for one status. */
    fun clearStatus(status: ProcessingStatus) {
        val cleared = state.applied.withoutStatus(status)
        state = state.copy(applied = cleared, draft = cleared)
        search()
    }

    fun updateSearchTerm(term: String) {
        state = state.copy(applied = state.applied.copy(searchTerm = term))
        // debounceJob, not searchJob: search() itself does `searchJob?.cancel()`, and this delay
        // coroutine calls search() once it fires. Storing the timer in searchJob would make that a
        // self-cancel — cancelling the very coroutine that is running the cancel — which happens to
        // be harmless today only because nothing after it suspends, and would silently break the
        // moment someone adds a suspending call (or an early return) between the cancel and the
        // relaunch. A dedicated field sidesteps the footgun entirely.
        debounceJob?.cancel()
        debounceJob =
            viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                search()
            }
    }

    fun selectType(fileType: String?) {
        state = state.copy(applied = state.applied.copy(fileType = fileType))
        search()
    }

    fun toggleIncludeInactive() {
        state =
            state.copy(
                applied = state.applied.copy(includeInactive = !state.applied.includeInactive),
            )
        search()
    }

    fun selectPeriod(
        granularity: ReportsGranularity,
        anchor: LocalDate,
    ) {
        state =
            state.copy(
                applied = state.applied.copy(granularity = granularity, anchor = anchor),
            )
        search()
    }

    fun selectCustomRange(
        from: LocalDate,
        to: LocalDate,
    ) {
        state =
            state.copy(
                applied =
                    state.applied.copy(
                        granularity = ReportsGranularity.CUSTOM,
                        customFrom = from,
                        customTo = to,
                    ),
            )
        search()
    }

    fun loadTypes() {
        viewModelScope.launch {
            runCatchingApi { api.getDocumentTypes() }
                .onSuccess { types -> state = state.copy(availableTypes = types) }
                .onError { uiError ->
                    // A missing type list must not block searching — fall back to the enum the
                    // server itself derives its list from.
                    log.w { "document types load failed, using DocumentType.entries: $uiError" }
                    state = state.copy(availableTypes = DocumentType.entries.map { it.name })
                }
        }
    }

    fun search() {
        val seq = ++requestSeq
        // A full search supersedes anything in flight, including a page fetch — its result would
        // belong to a filter that no longer applies.
        loadMoreJob?.cancel()
        searchJob?.cancel()
        state = state.copy(isLoading = true, error = null)
        searchJob = viewModelScope.launch { fetch(seq = seq, offset = 0, append = false) }
    }

    fun loadMore() {
        // Never start a page fetch while a full search is running: `state.items.size` would be the
        // offset for the *old* filter's list, and the request itself must never be able to cancel a
        // search — cancelling searchJob here previously lost the search's own result outright.
        if (state.isLoading || state.isLoadingMore || !state.hasMore) return
        val seq = ++requestSeq
        state = state.copy(isLoadingMore = true)
        loadMoreJob = viewModelScope.launch { fetch(seq = seq, offset = state.items.size, append = true) }
    }

    private suspend fun fetch(
        seq: Int,
        offset: Int,
        append: Boolean,
    ) {
        val f = state.applied
        val bounds = f.dateBounds()
        runCatchingApi {
            api.searchDocuments(
                fileType = f.fileType,
                uploadedBy = f.uploadedBy,
                startDate = bounds?.first,
                endDate = bounds?.second,
                vendorName = f.vendorName,
                invoiceNumber = f.invoiceNumber,
                minAmount = f.minAmount,
                maxAmount = f.maxAmount,
                bankName = f.bankName,
                statementNumber = f.statementNumber,
                // E2E 16.9: the archive can be narrowed by workflow state too (e.g. "Geprüft"), several
                // at once. Empty sends nothing, so the archive still lists every document by default.
                processingStatuses = f.statusesForRequest(),
                searchTerm = f.searchTerm.takeIf { it.isNotBlank() },
                includeInactive = f.includeInactive,
                offset = offset,
                limit = PAGE_SIZE,
            )
        }.onSuccess { response ->
            // A newer request superseded this one (filter changed mid-flight) — its result is stale,
            // discard it rather than clobber what the newer request already produced.
            if (seq != requestSeq) return@onSuccess
            log.d { "documents loaded: offset=$offset count=${response.items.size} total=${response.total}" }
            state =
                state.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    items = if (append) state.items + response.items else response.items,
                    total = response.total,
                    // `&& response.items.isNotEmpty()` guards against a page that comes back empty
                    // while the server still claims hasMore=true (an inconsistent, but observed,
                    // response). Without it, `state.items` never grows, the screen's scroll-position
                    // effect sees the same trailing item on every recomposition triggered by
                    // isLoadingMore flipping back to false, and loadMore() re-fires at the same
                    // offset forever.
                    hasMore = response.hasMore && response.items.isNotEmpty(),
                )
        }.onError(retry = { search() }) { uiError ->
            if (seq != requestSeq) return@onError
            log.e { "documents load failed at offset=$offset: $uiError" }
            state = state.copy(isLoading = false, isLoadingMore = false, error = uiError)
        }
    }
}
