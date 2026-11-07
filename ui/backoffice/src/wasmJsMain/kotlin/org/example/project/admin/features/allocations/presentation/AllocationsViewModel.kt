package org.example.project.admin.features.allocations.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.finance.presentation.BookkeepingStartController
import org.example.project.core.data.remote.AllocationApiClient
import org.example.project.core.data.remote.BankAccountApiClient
import org.example.project.core.data.remote.BookkeepingStartApiClient
import org.example.project.core.data.remote.KassenbuchApiClient
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.domain.dto.allocation.SuggestionDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError

/**
 * Drives Zuordnung: the queue of payments still carrying a residual, whichever candidate list the
 * chosen target kind needs, and the writes that settle a payment against one of them.
 *
 * **Every write ends in a re-read rather than a local edit.** The server recomputes both residuals
 * inside the same transaction as the allocation; a locally adjusted list would start disagreeing
 * with it, and a residual on screen that disagrees with the database is exactly the failure this
 * feature exists to prevent.
 *
 * The invoice candidate list is **not** narrowed to the payment's supplier: a queue row carries a
 * counterparty name and IBAN, not a resolved creditor. The endpoint takes a `supplierId` that no
 * caller passes — the matcher resolves suppliers for its own scoring but still asks for all of them.
 *
 * It also hosts the Buchungsbeginn banner, because the cut-off decides which rows the queue holds
 * at all. The banner's own behaviour lives in [BookkeepingStartController], which Rechnungen hosts
 * too — neither screen's ViewModel knows about the other.
 */
class AllocationsViewModel(
    private val api: AllocationApiClient,
    bookkeepingApi: BookkeepingStartApiClient,
    private val bankAccountApi: BankAccountApiClient,
    private val kassenbuchApi: KassenbuchApiClient,
) : ViewModel() {
    private companion object {
        /** How far back the Geldtransit picker looks. A till deposit is matched within days, not months. */
        const val RECENT_CASH_ENTRIES = 50
    }

    private val log = Logger.withTag("allocations")

    var state by mutableStateOf(AllocationsState())
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
            runCatchingApi { api.queue(filter = state.filter) }
                .onSuccess { response ->
                    log.d { "queue loaded: filter=${state.filter} rows=${response.items.size}" }
                    state = state.copy(isLoading = false, rows = response.items, total = response.total)
                }.onError(retry = { load() }) { uiError ->
                    log.e { "queue load failed: $uiError" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
        bookkeeping.load()
    }

    /** Returns without dispatching when the filter is already applied — tapping the active chip is not a request. */
    fun selectFilter(filter: AllocationDto.QueueFilter) {
        if (filter == state.filter) return
        state = state.clearedEditor().copy(filter = filter, expandedTransactionId = null)
        load()
    }

    /**
     * Collapsing clears the drafts **and the suggestions**, so neither a number typed nor a
     * suggestion computed against one payment can reach the next.
     */
    fun toggleExpanded(transactionId: Long) {
        if (state.expandedTransactionId == transactionId) {
            state = state.clearedEditor().copy(expandedTransactionId = null)
            return
        }
        state = state.clearedEditor().copy(expandedTransactionId = transactionId)
        loadOpenInvoices()
        loadSuggestions(transactionId)
    }

    /**
     * Closes the open editor without toggling another row on (the phone's Back). Same effect as
     * toggling the open row off: drafts and suggestions are dropped so nothing typed for one payment
     * reaches the next.
     */
    fun closeEditor() {
        if (state.expandedTransactionId != null) state = state.clearedEditor().copy(expandedTransactionId = null)
    }

    /**
     * Everything the expanded editor holds, back to its opening state — including the target kind,
     * so a new row never opens pointed at the previous row's sort of target.
     */
    private fun AllocationsState.clearedEditor(): AllocationsState =
        copy(
            openInvoices = emptyList(),
            openSalesInvoices = emptyList(),
            openInstalments = emptyList(),
            bankAccounts = emptyList(),
            cashEntries = emptyList(),
            selectedCounterId = null,
            amountDrafts = emptyMap(),
            suggestions = emptyList(),
            targetKind = "INVOICE",
            bookingTextDraft = "",
            accountNoDraft = "",
        )

    /**
     * Switching kind drops every draft: an amount typed against an invoice is an answer to a
     * different question than the same number booked as a fee, and carrying it over would let one
     * keystroke's worth of attention post the wrong sort of allocation.
     */
    fun selectTargetKind(kind: String) {
        if (kind == state.targetKind) return
        state =
            state.copy(
                targetKind = kind,
                amountDrafts = emptyMap(),
                bookingTextDraft = "",
                accountNoDraft = "",
                // A counter-leg answers "where did the money go?" for one kind of movement only:
                // the account picked for an Eigenübertrag is not an answer about a Geldtransit.
                // Carried over, a bank account id posts as a Kassenbuch header id and the server
                // accepts it whenever that id exists — see `TargetPickerTest`.
                selectedCounterId = null,
            )
        // Only on the first visit to the kind: the list belongs to the expanded row and is cleared
        // when that row collapses, so re-reading it on every chip tap would be the same answer at
        // the cost of a round trip the operator waits through.
        when (kind) {
            "CONTRACT_INSTALMENT" -> if (state.openInstalments.isEmpty()) loadOpenInstalments()
            "SALES_INVOICE" -> if (state.openSalesInvoices.isEmpty()) loadOpenSalesInvoices()
            "INTERNAL_TRANSFER" -> if (state.bankAccounts.isEmpty()) loadBankAccounts()
            "CASH_TRANSIT" -> if (state.cashEntries.isEmpty()) loadCashEntries()
            else -> Unit
        }
    }

    /** Which counter-leg the operator picked — the other half of a Geldtransit or an Eigenübertrag. */
    fun selectCounter(id: Long) {
        state = state.copy(selectedCounterId = id)
    }

    fun setBookingText(text: String) {
        state = state.copy(bookingTextDraft = text)
    }

    fun setAccountNo(text: String) {
        state = state.copy(accountNoDraft = text)
    }

    fun setAmount(
        key: String,
        typed: String,
    ) {
        state = state.copy(amountDrafts = state.amountDrafts + (key to typed))
    }

    /** The typed amount, or null when it must not be sent — the same rule the button's enabled state uses. */
    fun draftFor(
        key: String,
        restOpen: String,
    ): String? = canonicalAmountOrNull(state.amountDrafts[key].orEmpty(), restOpen)

    /**
     * The amount a terminal target would be booked with, or null when it must not be sent.
     *
     * `EXPENSE_NO_DOC` additionally needs a booking text — the server refuses it with a 400 — so the
     * button that calls [allocateTerminal] and [allocateTerminal] itself both read this one rule
     * (root `CLAUDE.md` rule 8).
     */
    fun terminalDraftFor(
        kind: String,
        restOpen: String,
    ): String? {
        val amount = draftFor(draftKey(kind, null), restOpen) ?: return null
        if (kind == "EXPENSE_NO_DOC" && state.bookingTextDraft.isBlank()) return null
        return amount
    }

    fun allocate(
        transactionId: Long,
        invoiceId: Long,
    ) {
        val row = state.rows.firstOrNull { it.transactionId == transactionId } ?: return
        val key = draftKey("INVOICE", invoiceId)
        val amount = draftFor(key, row.openAmount) ?: return

        postAllocation(
            request =
                AllocationDto.CreateRequestDto(
                    sourceKind = "BANK_TRANSACTION",
                    targetKind = "INVOICE",
                    amount = amount,
                    bankTransactionId = transactionId,
                    invoiceId = invoiceId,
                ),
            describe = "$amount of transaction $transactionId to invoice $invoiceId",
            clearDrafts = { copy(amountDrafts = amountDrafts - key) },
            // The invoice's own residual moved, so the candidate list is stale.
            reloadCandidates = ::loadOpenInvoices,
        )
    }

    /**
     * Settles a **credit** payment against a case-C (STANDALONE) invoice — the AR mirror of [allocate].
     *
     * The direction is not checked here: the queue can contain either direction, and the server's
     * direction guard lives in the matcher, not the write path. The editor does not need to know
     * whether a credit "really" settles a receivable — the operator picked the row.
     */
    fun allocateSalesInvoice(
        transactionId: Long,
        salesInvoiceId: Long,
    ) {
        val row = state.rows.firstOrNull { it.transactionId == transactionId } ?: return
        val key = draftKey("SALES_INVOICE", salesInvoiceId)
        val amount = draftFor(key, row.openAmount) ?: return

        postAllocation(
            request =
                AllocationDto.CreateRequestDto(
                    sourceKind = "BANK_TRANSACTION",
                    targetKind = "SALES_INVOICE",
                    amount = amount,
                    bankTransactionId = transactionId,
                    salesInvoiceId = salesInvoiceId,
                ),
            describe = "$amount of transaction $transactionId to sales invoice $salesInvoiceId",
            clearDrafts = { copy(amountDrafts = amountDrafts - key) },
            // The invoice's own residual moved, so the candidate list is stale.
            reloadCandidates = ::loadOpenSalesInvoices,
        )
    }

    /**
     * Settles a payment against one instalment of a contract's schedule — the rent or leasing debit
     * the matcher did not recognise.
     *
     * Booked exactly like an invoice, and deliberately **not** as an undocumented expense: the
     * instalment keeps its own residual, and an expense booking would leave the contract showing a
     * rate nobody paid while the money left the account.
     */
    fun allocateInstalment(
        transactionId: Long,
        instalmentId: Long,
    ) {
        val row = state.rows.firstOrNull { it.transactionId == transactionId } ?: return
        val key = draftKey("CONTRACT_INSTALMENT", instalmentId)
        val amount = draftFor(key, row.openAmount) ?: return

        postAllocation(
            request =
                AllocationDto.CreateRequestDto(
                    sourceKind = "BANK_TRANSACTION",
                    targetKind = "CONTRACT_INSTALMENT",
                    amount = amount,
                    bankTransactionId = transactionId,
                    contractInstalmentId = instalmentId,
                ),
            describe = "$amount of transaction $transactionId to instalment $instalmentId",
            clearDrafts = { copy(amountDrafts = amountDrafts - key) },
            // The instalment keeps its own residual, and the write just moved it.
            reloadCandidates = ::loadOpenInstalments,
        )
    }

    /**
     * Books the residual against a target that has no candidate to pick — a bank fee, or an expense
     * whose receipt does not exist.
     *
     * Both are **terminal**: nothing on the other side keeps a residual, so the write carries no
     * target id at all. The refusals are [terminalDraftFor]'s, applied before the request rather
     * than read back off a 400.
     */
    fun allocateTerminal(
        transactionId: Long,
        kind: String,
    ) {
        val row = state.rows.firstOrNull { it.transactionId == transactionId } ?: return
        val amount = terminalDraftFor(kind, row.openAmount) ?: return
        val key = draftKey(kind, null)

        postAllocation(
            request =
                AllocationDto.CreateRequestDto(
                    sourceKind = "BANK_TRANSACTION",
                    targetKind = kind,
                    amount = amount,
                    bankTransactionId = transactionId,
                    // Absence, not an empty string: an empty booking text is a value the server
                    // would have to store and later show as a blank line.
                    accountNo = state.accountNoDraft.takeIf { it.isNotBlank() },
                    bookingText = state.bookingTextDraft.takeIf { it.isNotBlank() },
                ),
            describe = "$amount of transaction $transactionId as $kind",
            clearDrafts = {
                copy(amountDrafts = amountDrafts - key, bookingTextDraft = "", accountNoDraft = "")
            },
            // Nothing else to re-read: a terminal target has no counterpart holding a residual.
            reloadCandidates = {},
        )
    }

    /**
     * Books an *Eigenübertrag*: money moved between two of our own accounts.
     *
     * The counter account is what makes it a transfer rather than a disappearance — without it the
     * booking records that money left an account and nothing about where it arrived. Refused here,
     * as the server refuses it with a 400 (root `CLAUDE.md` rule 8).
     */
    fun allocateTransfer(transactionId: Long) {
        allocateCounterLeg(transactionId, "INTERNAL_TRANSFER") { amount, counterId ->
            AllocationDto.CreateRequestDto(
                sourceKind = "BANK_TRANSACTION",
                targetKind = "INTERNAL_TRANSFER",
                amount = amount,
                bankTransactionId = transactionId,
                counterBankAccountId = counterId,
            )
        }
    }

    /**
     * Books a *Geldtransit*: till cash deposited at the bank, whose counter-leg is the Kassenbuch
     * entry that recorded the cash leaving the till.
     *
     * **Nothing here checks that the chosen entry matches the payment** — direction, amount and date
     * are the operator's reading, and a machine guess dressed as a rule would be trusted more than
     * it deserves. What is enforced is that an entry was named at all.
     */
    fun allocateTransit(transactionId: Long) {
        allocateCounterLeg(transactionId, "CASH_TRANSIT") { amount, counterId ->
            AllocationDto.CreateRequestDto(
                sourceKind = "BANK_TRANSACTION",
                targetKind = "CASH_TRANSIT",
                amount = amount,
                bankTransactionId = transactionId,
                counterKassenbuchHeaderId = counterId,
            )
        }
    }

    /**
     * The guard the two counter-leg writes share: both refuse without a chosen counter-leg or a
     * canonical amount.
     *
     * These kinds hold one draft at a time, keyed on the kind alone — the panel shows a single
     * amount field, not one per candidate.
     */
    private fun allocateCounterLeg(
        transactionId: Long,
        kind: String,
        request: (amount: String, counterId: Long) -> AllocationDto.CreateRequestDto,
    ) {
        val row = state.rows.firstOrNull { it.transactionId == transactionId } ?: return
        val counterId = state.selectedCounterId ?: return
        val key = draftKey(kind, null)
        val amount = draftFor(key, row.openAmount) ?: return

        postAllocation(
            request = request(amount, counterId),
            describe = "$amount of transaction $transactionId as $kind against $counterId",
            clearDrafts = { copy(amountDrafts = amountDrafts - key, selectedCounterId = null) },
            // A bank account and a Kassenbuch entry are not candidate lists with residuals — nothing
            // in either list changed, so re-reading them would only cost the operator a round trip.
            reloadCandidates = {},
        )
    }

    /**
     * The one dispatch body every write shares: post, drop the drafts that were sent, re-read.
     *
     * **Every write ends in a re-read of the queue**, because the server recomputes the residual in
     * the same transaction. [reloadCandidates] re-reads *only* what the write can additionally have
     * changed — the candidate list whose own residual moved — so a terminal or counter-leg booking,
     * which moves no candidate's residual, passes an empty one rather than a spurious round trip.
     *
     * [clearDrafts] runs on the already-saved state so each caller clears only the fields its own
     * panel owns.
     */
    private fun postAllocation(
        request: AllocationDto.CreateRequestDto,
        describe: String,
        clearDrafts: AllocationsState.() -> AllocationsState,
        reloadCandidates: () -> Unit,
    ) {
        state = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            runCatchingApi { api.allocate(request) }
                .onSuccess {
                    log.d { "allocated $describe" }
                    state = state.copy(isSaving = false).clearDrafts()
                    load()
                    reloadCandidates()
                }.onError { uiError ->
                    log.e { "allocation failed ($describe): $uiError" }
                    state = state.copy(isSaving = false, error = uiError)
                }
        }
    }

    /**
     * The amount a counter-leg target would be booked with, or null when it must not be sent — the
     * same rule the *Zuordnen* button's enabled state reads.
     */
    fun counterLegDraftFor(
        kind: String,
        restOpen: String,
    ): String? {
        if (state.selectedCounterId == null) return null
        return draftFor(draftKey(kind, null), restOpen)
    }

    /**
     * Books what the ladder proposed — only ever on this call, never by itself.
     *
     * A suggestion naming several invoices goes out as **one** batch, so it cannot half-succeed and
     * leave the payment assigned against a set that no longer sums.
     */
    fun acceptSuggestion(item: SuggestionDto.ItemDto) {
        val transactionId = state.expandedTransactionId ?: return
        val requests = requestsFor(item, transactionId)
        if (requests == null) {
            val ids = item.invoiceIds.ifEmpty { item.salesInvoiceIds }
            log.w { "suggestion names a target the editor has not loaded: $ids" }
            state = state.copy(error = UiError.Unknown(messageKey = AdminStringKeys.AllocationsSuggestionStale))
            return
        }

        state = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            runCatchingApi { api.allocateAll(requests) }
                .onSuccess {
                    log.d { "accepted a ${item.signal} suggestion as ${requests.size} allocation(s)" }
                    state = state.copy(isSaving = false)
                    load()
                    when (item.targetKind) {
                        "SALES_INVOICE" -> loadOpenSalesInvoices()
                        else -> loadOpenInvoices()
                    }
                    // Re-read the proposals too: the accepted one no longer applies, and leaving it
                    // on screen invites a second Übernehmen that only the server's over-allocation
                    // guard would stop — an error where the operator did nothing wrong.
                    state.expandedTransactionId?.let { loadSuggestions(it) }
                }.onError { uiError ->
                    log.e { "accepting a suggestion failed: $uiError" }
                    // The list stays: the operator must be able to try another one.
                    state = state.copy(isSaving = false, error = uiError)
                }
        }
    }

    /**
     * One request per target, or null when the editor cannot see a target the suggestion names.
     *
     * **The amount is never split arithmetically.** [SuggestionDto.ItemDto.amount] is the total the
     * payment would settle; each invoice's share is its own open amount — which is what made the
     * subset sum in the first place — read from the already-loaded candidate list. A missing id is a
     * state mismatch and yields null rather than a guess.
     */
    private fun requestsFor(
        item: SuggestionDto.ItemDto,
        transactionId: Long,
    ): List<AllocationDto.CreateRequestDto>? {
        if (item.targetKind == "SALES_INVOICE") {
            val ids = item.salesInvoiceIds
            if (ids.size <= 1) {
                return listOf(
                    suggestionRequest(
                        transactionId = transactionId,
                        item = item,
                        amount = item.amount,
                        invoiceId = null,
                        salesInvoiceId = ids.singleOrNull(),
                        contractInstalmentId = null,
                    ),
                )
            }
            val openById = state.openSalesInvoices.associateBy { it.salesInvoiceId }
            return ids.map { salesInvoiceId ->
                val invoice = openById[salesInvoiceId] ?: return null
                suggestionRequest(
                    transactionId = transactionId,
                    item = item,
                    amount = invoice.openAmount,
                    invoiceId = null,
                    salesInvoiceId = salesInvoiceId,
                    contractInstalmentId = null,
                )
            }
        }
        if (item.invoiceIds.size <= 1) {
            return listOf(
                suggestionRequest(
                    transactionId = transactionId,
                    item = item,
                    amount = item.amount,
                    invoiceId = item.invoiceIds.singleOrNull(),
                    salesInvoiceId = null,
                    contractInstalmentId = item.contractInstalmentId,
                ),
            )
        }
        val openById = state.openInvoices.associateBy { it.invoiceId }
        return item.invoiceIds.map { invoiceId ->
            val invoice = openById[invoiceId] ?: return null
            suggestionRequest(
                transactionId = transactionId,
                item = item,
                amount = invoice.openAmount,
                invoiceId = invoiceId,
                salesInvoiceId = null,
                contractInstalmentId = null,
            )
        }
    }

    /**
     * The suggestion travels with the request as [SuggestionDto.ItemDto.signal]: without it the
     * server records only *that* a suggestion was accepted, which cannot say which of the ladder's
     * signals earned it. Every item of a subset batch carries the same one — the subset sum is a
     * single answer, not several.
     */
    private fun suggestionRequest(
        transactionId: Long,
        item: SuggestionDto.ItemDto,
        amount: String,
        invoiceId: Long?,
        salesInvoiceId: Long?,
        contractInstalmentId: Long?,
    ) = AllocationDto.CreateRequestDto(
        sourceKind = "BANK_TRANSACTION",
        targetKind = item.targetKind,
        amount = amount,
        bankTransactionId = transactionId,
        invoiceId = invoiceId,
        salesInvoiceId = salesInvoiceId,
        contractInstalmentId = contractInstalmentId,
        matchedBy = "SUGGESTION_ACCEPTED",
        matchedSignal = item.signal,
    )

    /**
     * Books every candidate of the shown kind that carries an amount, **as one batch** — the editor's
     * single *Zuordnen*. One batch cannot half-succeed, so a payment never ends up assigned against a
     * set that no longer adds up. Refused (nothing sent) when nothing is selected or the amounts add up
     * to more than the payment's open amount — the same rule the button's enabled state reads.
     */
    fun allocateSelected(transactionId: Long) {
        val row = state.rows.firstOrNull { it.transactionId == transactionId } ?: return
        val kind = state.targetKind
        val picked = AllocationSelection.selected(kind, state, row.openAmount)
        if (!AllocationSelection.canAssign(picked, row.openAmount)) return
        val requests =
            picked.map { item ->
                AllocationDto.CreateRequestDto(
                    sourceKind = "BANK_TRANSACTION",
                    targetKind = kind,
                    amount = item.amount,
                    bankTransactionId = transactionId,
                    invoiceId = item.targetId.takeIf { kind == "INVOICE" },
                    salesInvoiceId = item.targetId.takeIf { kind == "SALES_INVOICE" },
                    contractInstalmentId = item.targetId.takeIf { kind == "CONTRACT_INSTALMENT" },
                )
            }
        state = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            runCatchingApi { api.allocateAll(requests) }
                .onSuccess {
                    log.d { "allocated ${requests.size} $kind target(s) of transaction $transactionId" }
                    state = state.copy(isSaving = false, amountDrafts = state.amountDrafts - picked.map { it.key }.toSet())
                    load()
                    when (kind) {
                        "SALES_INVOICE" -> loadOpenSalesInvoices()
                        "CONTRACT_INSTALMENT" -> loadOpenInstalments()
                        else -> loadOpenInvoices()
                    }
                }.onError { uiError ->
                    log.e { "batch allocation failed (transaction $transactionId): $uiError" }
                    state = state.copy(isSaving = false, error = uiError)
                }
        }
    }

    fun reverse(allocationId: Long) {
        state = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            runCatchingApi { api.reverse(allocationId) }
                .onSuccess {
                    log.d { "allocation $allocationId reversed" }
                    state = state.copy(isSaving = false)
                    load()
                    if (state.expandedTransactionId != null) {
                        if (state.targetKind == "SALES_INVOICE") loadOpenSalesInvoices() else loadOpenInvoices()
                    }
                }.onError { uiError ->
                    log.e { "reversal failed: $uiError" }
                    state = state.copy(isSaving = false, error = uiError)
                }
        }
    }

    /**
     * Writes the new cut-off, then re-reads the queue.
     *
     * The cut-off decides which rows the queue contains at all, so the list cannot be kept.
     */
    fun saveBookkeepingStart(date: String) = bookkeeping.save(date) { load() }

    /**
     * An empty answer is normal — most payments match nothing provable — so it is stored, not
     * treated as a failure.
     */
    private fun loadSuggestions(transactionId: Long) {
        viewModelScope.launch {
            runCatchingApi { api.suggestions(transactionId) }
                .onSuccess { response ->
                    // Only for the row still expanded: a slow answer must not reach the next payment.
                    if (state.expandedTransactionId != transactionId) return@onSuccess
                    state = state.copy(suggestions = response.items)
                }.onError { uiError ->
                    log.e { "suggestions load failed: $uiError" }
                    state = state.copy(error = uiError)
                }
        }
    }

    private fun loadOpenInvoices() {
        viewModelScope.launch {
            runCatchingApi { api.openInvoices() }
                .onSuccess { response -> state = state.copy(openInvoices = response.items) }
                .onError { uiError ->
                    log.e { "open invoices load failed: $uiError" }
                    state = state.copy(error = uiError)
                }
        }
    }

    private fun loadOpenSalesInvoices() {
        viewModelScope.launch {
            runCatchingApi { api.openSalesInvoices() }
                .onSuccess { response -> state = state.copy(openSalesInvoices = response.items) }
                .onError { uiError ->
                    log.e { "open sales invoices load failed: $uiError" }
                    state = state.copy(error = uiError)
                }
        }
    }

    private fun loadBankAccounts() {
        viewModelScope.launch {
            runCatchingApi { bankAccountApi.list() }
                .onSuccess { response -> state = state.copy(bankAccounts = response.items) }
                .onError { uiError ->
                    log.e { "bank accounts load failed: $uiError" }
                    state = state.copy(error = uiError)
                }
        }
    }

    /**
     * The recent cash-book entries, unfiltered — the counter-leg of a Geldtransit is chosen by the
     * operator, so narrowing the list by entry type would silently withhold candidates.
     */
    private fun loadCashEntries() {
        viewModelScope.launch {
            runCatchingApi { kassenbuchApi.getRecentEntries(limit = RECENT_CASH_ENTRIES) }
                .onSuccess { entries -> state = state.copy(cashEntries = entries) }
                .onError { uiError ->
                    log.e { "cash entries load failed: $uiError" }
                    state = state.copy(error = uiError)
                }
        }
    }

    private fun loadOpenInstalments() {
        viewModelScope.launch {
            runCatchingApi { api.openInstalments() }
                .onSuccess { response -> state = state.copy(openInstalments = response.items) }
                .onError { uiError ->
                    log.e { "open instalments load failed: $uiError" }
                    state = state.copy(error = uiError)
                }
        }
    }
}
