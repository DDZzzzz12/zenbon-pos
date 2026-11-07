package org.example.project.admin.features.kassenbuch.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.data.remote.CashBookApiClient
import org.example.project.core.data.remote.FiscalConfigurationApiClient
import org.example.project.core.data.remote.KassenbuchApiClient
import org.example.project.core.data.remote.KassenbuchCreateOutcome
import org.example.project.core.data.remote.ZBonApiClient
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.utils.KassenbuchBelegDateRule
import org.example.project.features.reports.presentation.ReportsGranularity
import org.example.project.features.reports.presentation.windowFor
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val DEFAULT_SHOP_ID: Long = 1L

/**
 * Drives the Kassenbuch screen: the cash-book tabs, the period being viewed, the two ways of
 * finding an entry — the client-side filter over the loaded period, and the explicit global search
 * across all periods — and the Kassensturz that closes a non-fiscal book's period.
 *
 * The entries endpoint parses date-only bounds (`LocalDate.parse(YYYY-MM-DD)`), so the reporting
 * window's time component is stripped before the request — same as `ZBonListViewModel`.
 */
class KassenbuchViewModel(
    private val kassenbuchApiClient: KassenbuchApiClient,
    private val cashBookApiClient: CashBookApiClient,
    private val zBonApiClient: ZBonApiClient,
    private val fiscalConfigurationApiClient: FiscalConfigurationApiClient,
    private val shopId: Long = DEFAULT_SHOP_ID,
) : ViewModel() {
    private val log = Logger.withTag("kassenbuch")

    /**
     * The shop's Beleg-age limit, loaded with the books. The default until then — and if the read
     * fails, which is not worth an error banner: the server enforces the limit regardless.
     */
    private var maxBelegAgeDays = KassenbuchBelegDateRule.DEFAULT_MAX_AGE_DAYS

    @OptIn(ExperimentalTime::class)
    private fun today(): LocalDate =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date

    var state by mutableStateOf(KassenbuchState(anchor = today()))
        private set

    fun clearError() {
        state = state.copy(error = null)
    }

    /** The tabs: every cash book of the shop, each with its own balance. Balances are never summed. */
    fun loadBooks() {
        viewModelScope.launch {
            runCatchingApi { cashBookApiClient.listBooks() }
                .onSuccess { books ->
                    log.d { "cash books loaded: ${books.size}" }
                    state = state.copy(books = books)
                }.onError(retry = { loadBooks() }) { uiError ->
                    log.e { "cash book load failed" }
                    state = state.copy(error = uiError)
                }
            runCatchingApi { fiscalConfigurationApiClient.getKassenbuchBelegAge(shopId) }
                .onSuccess { maxBelegAgeDays = it.maxAgeDays }
                .onError { log.w { "Beleg age limit not loaded; using the default" } }
        }
    }

    @OptIn(ExperimentalTime::class)
    fun load(
        granularity: ReportsGranularity,
        anchor: LocalDate,
    ) {
        state = state.copy(isLoading = true, hasLoaded = false, error = null, granularity = granularity, anchor = anchor)
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val (startIso, endIso) = granularity.windowFor(anchor, now)
        val start = startIso.substringBefore('T')
        val end = endIso.substringBefore('T')
        val bookId = state.selectedBookId
        viewModelScope.launch {
            runCatchingApi { kassenbuchApiClient.getEntryHistory(start, end, cashBookId = bookId) }
                .onSuccess { entries ->
                    log.d { "entries loaded for $granularity @ $anchor, book=$bookId: ${entries.size}" }
                    state = state.copy(isLoading = false, hasLoaded = true, entries = entries)
                }.onError(retry = { load(granularity, anchor) }) { uiError ->
                    log.e { "entry load failed for $granularity @ $anchor, book=$bookId" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }

    /**
     * Switches tab. [bookId] `null` is the "Alle Kassen" tab, which loads without a book filter.
     *
     * The fiscal tab additionally refreshes the Z-Bon status it displays read-only. Refetching on
     * every selection rather than caching keeps that line honest: a Z-Bon created at the till while
     * this screen was open would otherwise be reported as still-open business indefinitely.
     *
     * The open detail is dropped along with the global results: the selected entry belongs to the
     * book that was showing, so leaving it up describes a booking that is not in the visible ledger
     * — an entry of the Ladenkasse still filling the panel while the Tresor's (empty) table loads
     * beside it. The dashboard deep link is unaffected: it lands on the "Alle Kassen" tab and calls
     * `selectEntry` directly, and this early-returns when the tab does not actually change.
     */
    fun selectBook(bookId: Long?) {
        if (bookId == state.selectedBookId) return
        state =
            state.copy(
                selectedBookId = bookId,
                globalResults = null,
                globalResultsTruncated = false,
                selectedEntry = null,
            )
        load(state.granularity, state.anchor)
        if (state.books.firstOrNull { it.id == bookId }?.isFiscal == true) loadZBonStatus()
    }

    /**
     * The fiscal Ladenkasse's closing status, which is a Z-Bon and never a Kassensturz.
     *
     * Deliberately read-only and deliberately from the Z-Bon endpoint: the server's
     * `CashBookCloseStatusResolver` already answers "when was this book last closed?" for both kinds
     * of book, but it is not exposed over HTTP, so the shop's latest Z-Bon end time is the only
     * client-visible source. See the report accompanying this task.
     */
    private fun loadZBonStatus() {
        viewModelScope.launch {
            runCatchingApi { zBonApiClient.getLatestZBonEndTime() }
                .onSuccess { endTime ->
                    log.d { "latest z-bon end time: $endTime" }
                    state = state.copy(latestZBonEndTime = endTime)
                }.onError(retry = { loadZBonStatus() }) { uiError ->
                    log.e { "latest z-bon end time load failed" }
                    state = state.copy(error = uiError)
                }
        }
    }

    /**
     * Retypes the filter, and drops back out of global mode.
     *
     * Global search is offered **per term**, never sticky: a verdict reached for `zzzz` ("in keinem
     * Zeitraum gefunden") says nothing about `Testbuchung`, and leaving it on screen asserts
     * something untrue about the term now in the field. Clearing puts the operator back on the period
     * view, where the client-side filter answers immediately and the global escape is offered again.
     *
     * `globalResults` goes back to `null` — "not searching" — and never to an empty list, which would
     * mean "searched every period and found nothing" and reintroduce the same false verdict.
     */
    fun setFilterText(text: String) {
        if (text == state.filterText) return
        state = state.copy(filterText = text, globalResults = null, globalResultsTruncated = false)
    }

    /**
     * The escape from the period view: searches every period for the current filter term. Blank is
     * rejected here as well as by the server — an empty term would just return the newest entries
     * and read as a broken filter.
     */
    fun searchAllPeriods() {
        val query = state.filterText.trim()
        if (query.isEmpty()) {
            log.d { "global search skipped: blank term" }
            return
        }
        val bookIds = state.selectedBookId?.let { listOf(it) }
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi { kassenbuchApiClient.searchEntries(query, bookIds) }
                .onSuccess { results ->
                    val truncated = results.size >= KassenbuchApiClient.SEARCH_RESULT_LIMIT
                    log.d { "global search '$query' books=$bookIds: ${results.size} hits, truncated=$truncated" }
                    state =
                        state.copy(
                            isLoading = false,
                            globalResults = results,
                            globalResultsTruncated = truncated,
                        )
                }.onError(retry = { searchAllPeriods() }) { uiError ->
                    log.e { "global search failed for '$query'" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }

    /** Leaves global mode. `null` results mean "not searching", which is not the same as "no hits". */
    fun clearGlobalSearch() {
        state = state.copy(globalResults = null, globalResultsTruncated = false)
    }

    /**
     * Takes a global-search hit back into the normal period view: moves the stepper to the period
     * that actually contains the entry, drops global mode, and opens the entry's detail.
     *
     * The period comes from [attestedDateOf] on `createdAt`, never from `belegDatum` — see that
     * function for why picking the wrong one strands the operator on a period the entry is not in.
     *
     * [filterText] is deliberately kept: the server matches Belegnummer/Belegtext with the same
     * predicate [KassenbuchState.visibleEntries] applies, so the entry that was just clicked is
     * guaranteed to survive the client filter, and the operator's search context is preserved.
     */
    fun jumpToEntry(entry: KassenbuchDto.KassenbuchGroupedSummaryDto) {
        val attested = attestedDateOf(entry.createdAt)
        if (attested == null) {
            // Open it anyway rather than doing nothing: an unparseable timestamp is no reason to
            // withhold the booking the operator asked for. The period simply does not move.
            log.w { "period jump skipped: unparseable createdAt '${entry.createdAt}' on header ${entry.headerId}" }
            state = state.copy(globalResults = null, globalResultsTruncated = false)
            selectEntry(entry.headerId)
            return
        }
        state = state.copy(globalResults = null, globalResultsTruncated = false)
        load(state.granularity, attested)
        selectEntry(entry.headerId)
    }

    fun selectEntry(headerId: Long) {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi { kassenbuchApiClient.getGroupedEntryDetail(headerId) }
                .onSuccess { detail ->
                    log.d { "entry detail loaded for header $headerId" }
                    state = state.copy(isLoading = false, selectedEntry = detail)
                }.onError(retry = { selectEntry(headerId) }) { uiError ->
                    log.e { "entry detail load failed for header $headerId" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }

    fun clearSelectedEntry() {
        state = state.copy(selectedEntry = null)
    }

    /**
     * Fetches the next Kassensturz period and opens the dialog on it.
     *
     * The fiscal check is the service-side half of the same guard the UI applies by not offering the
     * control (CLAUDE.md rule 8): `CashBookCloseServiceImpl.requireNonFiscal` rejects a fiscal close
     * outright, because the Ladenkasse is closed by the Z-Bon. Refusing here too means a stray call
     * — a stale composition, a future caller — cannot turn into a 422 the operator has to interpret.
     *
     * The preview creates nothing, so a failure (typically "nothing new to close") is safe to
     * surface through the normal error path with the dialog left shut.
     */
    fun openKassensturz() {
        val book = state.selectedBook
        if (book == null) {
            log.w { "Kassensturz skipped: no cash book selected" }
            return
        }
        if (book.isFiscal) {
            log.w { "Kassensturz refused for fiscal book ${book.id}: the Ladenkasse is closed by the Z-Bon" }
            return
        }
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi { cashBookApiClient.closePreview(book.id) }
                .onSuccess { preview ->
                    log.d { "close preview for book ${book.id}: ${preview.periodStart}..${preview.periodEnd}" }
                    state = state.copy(isLoading = false, kassensturz = KassensturzUiState(preview))
                }.onError(retry = { openKassensturz() }) { uiError ->
                    log.e { "close preview failed for book ${book.id}" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }

    fun setCountedBalance(text: String) {
        state = state.copy(kassensturz = state.kassensturz?.copy(countedInput = text))
    }

    fun setKassensturzNotes(text: String) {
        state = state.copy(kassensturz = state.kassensturz?.copy(notes = text))
    }

    fun dismissKassensturz() {
        state = state.copy(kassensturz = null)
    }

    /**
     * Records the Kassensturz, then reloads: the close books the difference as its own entry and so
     * moves both the book's balance (the tab chip) and the period's row list.
     *
     * The counted amount crosses the wire as the exact decimal string `Decimal.toPlainString()`
     * produces — it is never routed through a `Double`, and `"0"` is a legitimate value here.
     * `validate()` is the DTO's own shape check, the same one the server applies; failing it means
     * the guards above let something through, so it is logged rather than silently sent.
     */
    fun confirmKassensturz() {
        val sturz = state.kassensturz ?: return
        val counted = sturz.countedBalance
        if (sturz.isSubmitting) return
        if (counted == null || !sturz.canConfirm) {
            log.w { "Kassensturz submit refused: counted balance '${sturz.countedInput}' is not usable" }
            // Confirm is never greyed out for a missing count (UI-E2-06): the press marks the field.
            state = state.copy(kassensturz = sturz.copy(validationAttempt = sturz.validationAttempt + 1))
            return
        }
        val bookId = sturz.preview.cashBookId
        val request =
            CashBookDto.CloseRequestDto(
                countedBalance = counted.toPlainString(),
                notes = sturz.notes.trim().ifBlank { null },
            )
        val problems = request.validate()
        if (problems.isNotEmpty()) {
            log.e { "Kassensturz submit refused for book $bookId: $problems" }
            return
        }

        state = state.copy(kassensturz = sturz.copy(isSubmitting = true), error = null)
        viewModelScope.launch {
            runCatchingApi { cashBookApiClient.close(bookId, request) }
                .onSuccess { result ->
                    log.d { "Kassensturz booked for book $bookId: difference ${result.difference}" }
                    state = state.copy(kassensturz = null)
                    loadBooks()
                    load(state.granularity, state.anchor)
                }.onError(retry = { confirmKassensturz() }) { uiError ->
                    log.e { "Kassensturz failed for book $bookId" }
                    // The dialog stays open with the count intact: the operator counted real cash,
                    // and making them count again because the request failed would be its own defect.
                    state = state.copy(kassensturz = state.kassensturz?.copy(isSubmitting = false), error = uiError)
                }
        }
    }

    /**
     * Opens the "+ Buchung" form, and refuses to for the fiscal Ladenkasse.
     *
     * This is the ViewModel half of the same guard the UI applies by not drawing the button
     * (CLAUDE.md rule 8). A fiscal Kassenbuch entry is signed by a TSE and needs a signing terminal
     * — `KassenbuchServiceImpl` resolves one via `requireSigningTerminal` whenever the target book is
     * fiscal, and the web admin has no terminal to offer. Fiscal entries are made at the till, in
     * composeApp; refusing here means a stray caller cannot turn that into an error the operator has
     * to interpret.
     *
     * The book is captured into the form now rather than read back at submit time: switching tabs
     * mid-form would otherwise move the booking to a different ledger without saying so.
     */
    fun openEntryForm() {
        val book = state.selectedBook
        if (book == null) {
            log.w { "entry form skipped: no cash book selected" }
            return
        }
        if (book.isFiscal) {
            log.w { "entry form refused for fiscal book ${book.id}: fiscal entries need a signing terminal" }
            return
        }
        val opened =
            KassenbuchEntryFormState(
                cashBookId = book.id,
                cashBookName = book.name,
                belegDatum = germanDateOf(today()),
                maxBelegAgeDays = maxBelegAgeDays,
                today = today(),
                availableCash = book.currentBalance,
            )
        entryFormOriginal = opened
        state = state.copy(entryForm = opened)
    }

    /** The form as [openEntryForm] built it: the pristine baseline for [isEntryFormDirty]. */
    private var entryFormOriginal: KassenbuchEntryFormState? = null

    /** True when the open entry form differs from what it opened with; false while it is closed. */
    val isEntryFormDirty: Boolean
        get() {
            val form = state.entryForm ?: return false
            val original = entryFormOriginal ?: return false
            return form.isDirtyFrom(original)
        }

    fun dismissEntryForm() {
        entryFormOriginal = null
        state = state.copy(entryForm = null)
    }

    /** Applies [change] to the open form, or does nothing when it is closed. */
    private fun editForm(change: (KassenbuchEntryFormState) -> KassenbuchEntryFormState) {
        val form = state.entryForm ?: return
        state = state.copy(entryForm = change(form))
    }

    fun setEntryType(type: KassenbuchEntryType) = editForm { it.copy(entryType = type) }

    fun setEntryBelegDatum(text: String) = editForm { it.copy(belegDatum = text) }

    fun setEntryLateBookingReason(text: String) = editForm { it.copy(lateBookingReason = text.take(KassenbuchBelegDateRule.MAX_LATE_BOOKING_REASON_LENGTH)) }

    fun clearLateBookingAwaitingApproval() {
        state = state.copy(lateBookingAwaitingApproval = false)
    }

    fun setEntryBelegtext(text: String) = editForm { it.copy(belegtext = text) }

    fun setEntryNote(text: String) = editForm { it.copy(nachricht = text) }

    fun setEntryLine(
        index: Int,
        line: EntryLineInput,
    ) = editForm { form ->
        if (index !in form.lines.indices) return@editForm form
        form.copy(lines = form.lines.toMutableList().also { it[index] = line })
    }

    fun addEntryLine() = editForm { it.copy(lines = it.lines + EntryLineInput()) }

    /** Removing the last line is refused: a Beleg with no lines is not a booking. */
    fun removeEntryLine(index: Int) =
        editForm { form ->
            if (form.lines.size <= 1 || index !in form.lines.indices) return@editForm form
            form.copy(lines = form.lines.toMutableList().also { it.removeAt(index) })
        }

    /** The document-upload section's only output. What it means — a mandatory Beleg — is decided here. */
    fun setEntryDocument(documentId: Long) =
        editForm {
            log.d { "Beleg $documentId attached to the entry form" }
            it.copy(documentId = documentId)
        }

    /**
     * Books the entry, and refuses three ways before it can reach the server.
     *
     * The **fiscal** check is re-run against the loaded book list rather than trusted from the form:
     * it is the guard's second level and must hold even if a form was somehow opened for the
     * Ladenkasse. The **document** check (part of [KassenbuchEntryFormState.canSubmit]) is the client
     * half of `KassenbuchServiceImpl`'s "documentId is required": confirm stays pressable, and a press
     * without a Beleg is refused here with the fields and lines at fault marked. `validate()` is the DTO's own
     * matrix, the same one the server applies; failing it means the two checks above let something
     * through, so it is logged rather than silently sent.
     */
    fun submitEntry() {
        val form = state.entryForm ?: return
        if (form.isSubmitting) return
        val book = state.books.firstOrNull { it.id == form.cashBookId }
        if (book == null || book.isFiscal) {
            log.e { "entry submit refused for book ${form.cashBookId}: fiscal or unknown cash book" }
            return
        }
        if (!form.canSubmit) {
            // Validate on press (UI-E2-06): Confirm is never greyed out for an incomplete form; this
            // marks the fields at fault and the dialog scrolls to the first one.
            log.w { "entry submit refused for book ${form.cashBookId}: the form is incomplete (Beleg attached: ${form.documentId != null})" }
            state = state.copy(entryForm = form.copy(validationAttempt = form.validationAttempt + 1))
            return
        }
        val request = form.toRequest()
        val problems = request.validate()
        if (problems.isNotEmpty()) {
            log.e { "entry submit refused for book ${form.cashBookId}: $problems" }
            return
        }

        state = state.copy(entryForm = form.copy(isSubmitting = true), error = null)
        viewModelScope.launch {
            runCatchingApi { kassenbuchApiClient.createEntry(request) }
                .onSuccess { outcome ->
                    when (outcome) {
                        is KassenbuchCreateOutcome.Created -> {
                            log.d { "entry ${outcome.group.belegNr} booked into book ${form.cashBookId}" }
                            state = state.copy(entryForm = null)
                            loadBooks()
                            load(state.granularity, state.anchor)
                        }
                        // Nothing is booked yet: no reload; the screen says it is waiting for a manager.
                        KassenbuchCreateOutcome.AwaitingApproval -> {
                            log.d { "late booking for book ${form.cashBookId} sent to a manager for approval" }
                            state = state.copy(entryForm = null, lateBookingAwaitingApproval = true)
                        }
                    }
                }.onError(retry = { submitEntry() }) { uiError ->
                    log.e { "entry creation failed for book ${form.cashBookId}" }
                    // The form stays open and filled: the operator typed a Beleg and uploaded a
                    // file, and making them redo both because the request failed is its own defect.
                    state = state.copy(entryForm = state.entryForm?.copy(isSubmitting = false), error = uiError)
                }
        }
    }
}

/**
 * A [LocalDate] as the German `DD.MM.YYYY` that `belegDatum` expects.
 *
 * That field is the one date on this screen that is **not** ISO — see
 * `CreateKassenbuchEntryRequestDto`, where every other date crosses the wire as `YYYY-MM-DD`. Sending
 * an ISO string here would store a Belegdatum no DATEV export can read, and nothing would reject it:
 * the column is a plain `varchar`.
 */
internal fun germanDateOf(date: LocalDate): String {
    val day =
        date.day
            .toString()
            .padStart(2, '0')
    val month =
        date.month.number
            .toString()
            .padStart(2, '0')
    return "$day.$month.${date.year}"
}

/**
 * The date a Kassenbuch entry is filed under, taken from its `createdAt`.
 *
 * **This must be `createdAt`, not `belegDatum`.** `KassenbuchRepositoryImpl.getEntriesByDateRange`
 * filters the period with `header.createdAt.date in start..end` — deliberately (commit `7f8bee3be`
 * with migration V94, which realigned `kassenbuch_header.created_at` to the TSE-attested signature
 * time). `belegDatum` is an operator-typed `varchar` that may name any date at all, so a backdated
 * or imported entry would send the stepper to a period the entry is not loaded in: the operator
 * clicks a search hit and lands on a table that does not contain it.
 *
 * The column is a naive `datetime` and the server compares its `.date` with no timezone conversion,
 * so the date is read straight off the string rather than through an `Instant` and a local offset —
 * converting would shift the day near midnight and can land a whole period away. Taking the text
 * before `'T'` also tolerates every `LocalDateTime.toString()` shape (with or without seconds or
 * fractional digits).
 *
 * @return `null` when the timestamp cannot be read; callers must still honour the entry itself.
 */
internal fun attestedDateOf(createdAt: String): LocalDate? = runCatching { LocalDate.parse(createdAt.substringBefore('T')) }.getOrNull()
