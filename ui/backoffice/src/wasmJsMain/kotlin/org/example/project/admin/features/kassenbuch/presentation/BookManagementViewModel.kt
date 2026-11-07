package org.example.project.admin.features.kassenbuch.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.remote.CashBookApiClient
import org.example.project.core.data.remote.FiscalConfigurationApiClient
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError

/** adminApp is single-tenant, same as composeApp — the config endpoints are still shop-scoped. */
private const val DEFAULT_SHOP_ID: Long = 1L

/**
 * Drives the book-management dialog: the shop's cash books, and the automatic Geldtransit that
 * makes Phase A's Z-Bon transfer actually usable by an operator.
 *
 * Deliberately a **separate** ViewModel from [KassenbuchViewModel] rather than more state on it.
 * The two answer different questions — "what was booked in this period?" versus "which ledgers
 * exist and where do the takings go?" — and only this one needs the shop-configuration client. The
 * screen reloads the tabs after the dialog closes, which is the only coupling between them.
 *
 * Every mutating path re-checks its guard against the **loaded server list**, not against the form
 * it is submitting. That is the point of the second level (CLAUDE.md rule 8): a form whose switch
 * was flipped before the books refreshed must not be able to send a request the server will refuse.
 */
class BookManagementViewModel(
    private val cashBookApiClient: CashBookApiClient,
    private val fiscalConfigurationApiClient: FiscalConfigurationApiClient,
    private val shopId: Long = DEFAULT_SHOP_ID,
    /**
     * Whether the signed-in user holds `TERMINAL_MANAGE`. A function, not a value: the app shares one
     * `ViewModelStoreOwner`, so this ViewModel outlives a logout and a captured Boolean would carry
     * the previous user's capability into the next session.
     */
    private val canManageTerminals: () -> Boolean = { false },
) : ViewModel() {
    private val log = Logger.withTag("cash-books")

    var state by mutableStateOf(BookManagementState())
        private set

    fun clearError() {
        state = state.copy(error = null)
    }

    fun clearSuccessMessage() {
        state = state.copy(successMessage = null)
    }

    /**
     * Loads the books, the stored transfer target and the Beleg-age limit. The books and target are
     * needed before the picker is usable, the limit before its field can be saved.
     */
    fun load() {
        resetForOpen()
        fetch()
    }

    /**
     * Forgets that the books were ever read. The ViewModel outlives the dialog, so without this a
     * reopened dialog would present the previous visit's list as if it were current.
     */
    fun resetForOpen() {
        state = state.copy(isLoading = true, isLoaded = false, error = null)
    }

    // Both list reads below pass includeInactive = true: the server omits a deactivated book from the
    // default list, so without it a book vanished from this dialog the moment it was saved inactive and
    // could never be seen or re-activated (the row's "inactive" tag and the active-first sort assume it is here).

    /** Re-reads everything without blanking the list — used after a save, where the dialog stays up. */
    private fun fetch() {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi {
                Triple(
                    cashBookApiClient.listBooks(includeInactive = true),
                    fiscalConfigurationApiClient.getCashBookTransferTarget(shopId),
                    fiscalConfigurationApiClient.getKassenbuchBelegAge(shopId),
                )
            }.onSuccess { (books, target, belegAge) ->
                log.d { "loaded ${books.size} cash books, transfer target = ${target.cashBookId ?: "off"}" }
                state =
                    state.copy(
                        isLoading = false,
                        isLoaded = true,
                        books = books,
                        transferTargetId = target.cashBookId,
                        savedTransferTargetId = target.cashBookId,
                        isTransferTargetLoaded = true,
                        belegAgeInput = belegAge.maxAgeDays.toString(),
                        savedBelegAgeDays = belegAge.maxAgeDays,
                    )
            }.onError(retry = { load() }) { uiError ->
                log.e { "cash book / transfer target load failed" }
                state = state.copy(isLoading = false, error = uiError)
            }
        }
    }

    // -------------------------------------------------------------------------
    // create / edit form
    // -------------------------------------------------------------------------

    /** A new book is always non-fiscal — the request DTO has no `isFiscal` field to set. */
    fun openCreateForm() {
        val opened = CashBookFormState(takenNames = takenBookNames(state.books, exceptBookId = null))
        state = state.copy(form = opened, formOriginal = opened)
    }

    fun openEditForm(bookId: Long) {
        val book = state.books.firstOrNull { it.id == bookId }
        if (book == null) {
            log.w { "edit refused: cash book $bookId is not in the loaded list" }
            return
        }
        val opened = CashBookFormState.of(book, takenBookNames(state.books, exceptBookId = bookId))
        state = state.copy(form = opened, formOriginal = opened)
        loadSigningTerminal(book)
    }

    fun dismissForm() {
        state = state.copy(form = null, formOriginal = null, signingTerminal = SigningTerminalPicker(), signingBookId = null)
    }

    private fun editForm(change: (CashBookFormState) -> CashBookFormState) {
        val form = state.form ?: return
        state = state.copy(form = change(form))
    }

    fun setBookName(text: String) = editForm { it.copy(name = text) }

    fun setBookDatevAccount(text: String) = editForm { it.copy(datevAccount = text) }

    fun setBookKost1(text: String) = editForm { it.copy(kost1 = text) }

    fun setBookKost2(text: String) = editForm { it.copy(kost2 = text) }

    fun setBookCloseInterval(interval: CashBookCloseInterval) = editForm { it.copy(closeInterval = interval) }

    fun setBookSortOrder(text: String) = editForm { it.copy(sortOrder = text) }

    /**
     * Flips the active switch, refusing a deactivation the server would refuse.
     *
     * This is the state half of the guard the UI also applies by disabling the switch: the fiscal
     * Ladenkasse can never be deactivated, and a non-fiscal book only while its balance is zero.
     * Re-*activating* is never blocked — nothing is stranded by bringing a book back.
     */
    fun setBookActive(active: Boolean) {
        val form = state.form ?: return
        if (!active && !form.canDeactivate) {
            log.w { "deactivation refused for book ${form.bookId}: ${form.deactivationBlock}" }
            return
        }
        state = state.copy(form = form.copy(isActive = active))
    }

    /**
     * Creates or updates the book, and refuses before the request when the guard says no.
     *
     * The deactivation check is re-run against the **loaded book** rather than the form's own
     * `isFiscal`/`currentBalance`, so a form opened before a deposit landed cannot deactivate a
     * book that has since taken cash.
     */
    fun submitBook() {
        val form = state.form ?: return
        if (form.isSubmitting) return
        if (!form.canSubmit) {
            log.w { "book submit refused: the form is incomplete or its name is taken" }
            // Save is never disabled by the input: the refused click reveals every field error.
            state = state.copy(form = form.copy(validationAttempt = form.validationAttempt + 1))
            return
        }
        if (isNameTakenNow(form)) {
            // The list moved since the form opened; flag the name like a server refusal would.
            state = state.copy(form = form.copy(serverRefusedName = form.name, validationAttempt = form.validationAttempt + 1))
            return
        }
        if (!form.isActive && !isDeactivatableNow(form.bookId)) return

        val problems = if (form.isEdit) form.toUpdateRequest().validate() else form.toCreateRequest().validate()
        if (problems.isNotEmpty()) {
            log.e { "book submit refused for ${form.bookId}: $problems" }
            return
        }

        state = state.copy(form = form.copy(isSubmitting = true), error = null)
        viewModelScope.launch {
            runCatchingApi { saveBook(form) }
                .onSuccess { saved ->
                    log.d { "cash book ${saved.id} saved: ${saved.name}, active=${saved.isActive}" }
                    state = state.copy(form = null)
                    fetch()
                }.onError(retry = { submitBook() }) { uiError ->
                    // The form stays open and filled: the operator typed it, and making them retype
                    // it because the request failed is its own defect.
                    if (uiError is UiError.FromServer && uiError.code == PosErrorCode.CASH_BOOK_NAME_ALREADY_TAKEN) {
                        // Most likely a deactivated book the loaded list does not carry. Flag the
                        // field in place instead of raising the generic error dialog.
                        log.w { "cash book save refused: name '${form.name.trim()}' is taken on the server" }
                        state =
                            state.copy(
                                form =
                                    state.form?.let { current ->
                                        current.copy(
                                            isSubmitting = false,
                                            serverRefusedName = form.name,
                                            validationAttempt = current.validationAttempt + 1,
                                        )
                                    },
                            )
                        return@onError
                    }
                    log.e { "cash book save failed for ${form.bookId}" }
                    state = state.copy(form = state.form?.copy(isSubmitting = false), error = uiError)
                }
        }
    }

    private suspend fun saveBook(form: CashBookFormState): CashBookDto.ResponseDto {
        val bookId = form.bookId ?: return cashBookApiClient.createBook(form.toCreateRequest())
        return cashBookApiClient.updateBook(bookId, form.toUpdateRequest())
    }

    /**
     * Whether [form]'s name collides with another book in the **current** loaded list — re-checked
     * here rather than trusted from the form, whose collision set was taken when it opened. Logs and
     * returns true when it does, so no request is made.
     */
    private fun isNameTakenNow(form: CashBookFormState): Boolean {
        val taken = normalizedBookName(form.name) in takenBookNames(state.books, exceptBookId = form.bookId)
        if (taken) log.w { "book submit refused: another loaded book is already named '${form.name.trim()}'" }
        return taken
    }

    /**
     * Whether the server's copy of [bookId] may be deactivated right now. Logs and returns false
     * when it may not, so no request is made.
     */
    private fun isDeactivatableNow(bookId: Long?): Boolean {
        val stored = state.books.firstOrNull { it.id == bookId }
        if (stored == null) {
            // Creating a book is never a deactivation; an unknown id on edit means the list moved
            // under the form, and guessing would be worse than refusing.
            log.e { "deactivation refused: cash book $bookId is not in the loaded list" }
            return false
        }
        if (stored.isFiscal) {
            log.e { "deactivation refused: '${stored.name}' is the fiscal Ladenkasse" }
            return false
        }
        if (!isZeroBalance(stored.currentBalance)) {
            log.e { "deactivation refused: '${stored.name}' still holds ${stored.currentBalance}" }
            return false
        }
        return true
    }

    // -------------------------------------------------------------------------
    // transfer target
    // -------------------------------------------------------------------------

    /**
     * Picks the book each Z-Bon moves the day's takings into. `null` switches the transfer **off**,
     * which is a legitimate choice and the shop's default — not an absent value.
     *
     * A non-null id must name an active, non-fiscal book, mirroring the PUT's server-side check.
     */
    fun setTransferTarget(bookId: Long?) {
        if (bookId != null && state.transferTargetCandidates.none { it.id == bookId }) {
            log.w { "transfer target $bookId refused: not an active, non-fiscal cash book" }
            return
        }
        state = state.copy(transferTargetId = bookId)
    }

    fun saveTransferTarget() {
        if (!state.isTransferTargetLoaded) {
            log.w { "transfer target save refused: the stored value has not been read yet" }
            return
        }
        val target = state.transferTargetId
        if (target != null && state.transferTargetCandidates.none { it.id == target }) {
            log.e { "transfer target save refused: $target is not an active, non-fiscal cash book" }
            return
        }

        state = state.copy(isSavingTransferTarget = true, error = null)
        viewModelScope.launch {
            runCatchingApi {
                fiscalConfigurationApiClient.updateCashBookTransferTarget(
                    shopId,
                    ShopConfigurationDto.UpdateCashBookTransferTargetRequestDto(cashBookId = target),
                )
            }.onSuccess { saved ->
                log.d { "transfer target saved: ${saved.cashBookId ?: "off"}" }
                state =
                    state.copy(
                        isSavingTransferTarget = false,
                        transferTargetId = saved.cashBookId,
                        savedTransferTargetId = saved.cashBookId,
                    )
            }.onError(retry = { saveTransferTarget() }) { uiError ->
                log.e { "transfer target save failed" }
                state = state.copy(isSavingTransferTarget = false, error = uiError)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Beleg-age limit
    // -------------------------------------------------------------------------

    fun setBelegAgeInput(text: String) {
        state = state.copy(belegAgeInput = text)
    }

    /**
     * Saves how many days back a new entry's Beleg date may lie. Refused before the stored value
     * has been read, and for anything the server would refuse (outside 1..366), mirroring its 422.
     */
    fun saveBelegAge() {
        val days = state.parsedBelegAgeDays
        if (state.savedBelegAgeDays == null || days == null) {
            log.w { "Beleg age save refused: not loaded yet, or '${state.belegAgeInput}' is not 1..366" }
            return
        }
        state = state.copy(isSavingBelegAge = true, error = null)
        viewModelScope.launch {
            runCatchingApi {
                fiscalConfigurationApiClient.updateKassenbuchBelegAge(
                    shopId,
                    ShopConfigurationDto.UpdateKassenbuchBelegAgeRequestDto(maxAgeDays = days),
                )
            }.onSuccess { saved ->
                log.d { "Beleg age limit saved: ${saved.maxAgeDays} days" }
                state =
                    state.copy(
                        isSavingBelegAge = false,
                        belegAgeInput = saved.maxAgeDays.toString(),
                        savedBelegAgeDays = saved.maxAgeDays,
                    )
            }.onError(retry = { saveBelegAge() }) { uiError ->
                log.e { "Beleg age limit save failed" }
                state = state.copy(isSavingBelegAge = false, error = uiError)
            }
        }
    }

    // -------------------------------------------------------------------------
    // signing till of the fiscal book
    // -------------------------------------------------------------------------

    /**
     * Loads the tills that may sign for the fiscal [book]. Hidden (and nothing requested) without
     * `TERMINAL_MANAGE` — the server refuses the same call, this keeps the control from existing.
     * A lone eligible till is only *proposed*: nothing is saved until the operator confirms.
     */
    fun loadSigningTerminal(book: CashBookDto.ResponseDto) {
        if (!canManageTerminals() || !book.isFiscal) {
            state = state.copy(signingTerminal = SigningTerminalPicker(), signingBookId = null)
            return
        }
        viewModelScope.launch {
            runCatchingApi { cashBookApiClient.listSigningTerminalCandidates(book.id) }
                .onSuccess { candidates ->
                    val proposal = book.signingTerminalId == null && candidates.size == 1
                    state =
                        state.copy(
                            signingTerminal =
                                SigningTerminalPicker(
                                    visible = true,
                                    candidates = candidates,
                                    selectedId = book.signingTerminalId ?: candidates.singleOrNull()?.terminalId,
                                    savedId = book.signingTerminalId,
                                    isProposal = proposal,
                                ),
                            signingBookId = book.id,
                        )
                }.onError(retry = { loadSigningTerminal(book) }) { uiError ->
                    log.e { "signing till candidates load failed for ${book.id}" }
                    state = state.copy(error = uiError)
                }
        }
    }

    fun selectSigningTerminal(terminalId: Long?) {
        val picker = state.signingTerminal
        if (!picker.visible) return
        if (terminalId != null && picker.candidates.none { it.terminalId == terminalId }) {
            log.w { "signing till $terminalId refused: not an eligible candidate" }
            return
        }
        state = state.copy(signingTerminal = picker.copy(selectedId = terminalId, isProposal = false))
    }

    fun saveSigningTerminal() {
        val bookId = state.signingBookId ?: return
        val picker = state.signingTerminal
        if (!canManageTerminals() || !picker.visible || picker.isSaving) return
        state = state.copy(signingTerminal = picker.copy(isSaving = true), error = null)
        viewModelScope.launch {
            runCatchingApi { cashBookApiClient.setSigningTerminal(bookId, picker.selectedId) }
                .onSuccess { updated ->
                    log.d { "signing till of book $bookId saved: ${updated.signingTerminalId ?: "none"}" }
                    state =
                        state.copy(
                            signingTerminal =
                                state.signingTerminal.copy(
                                    isSaving = false,
                                    isProposal = false,
                                    selectedId = updated.signingTerminalId,
                                    savedId = updated.signingTerminalId,
                                ),
                            successMessage = AdminStringKeys.CashBookSigningTerminalSaved,
                        )
                    reloadBooks()
                }.onError(retry = { saveSigningTerminal() }) { uiError ->
                    log.e { "signing till save failed for book $bookId" }
                    state = state.copy(signingTerminal = state.signingTerminal.copy(isSaving = false), error = uiError)
                }
        }
    }

    /** Refreshes the book list without touching the open form or the picker. */
    private fun reloadBooks() {
        viewModelScope.launch {
            runCatchingApi { cashBookApiClient.listBooks(includeInactive = true) }
                .onSuccess { books -> state = state.copy(books = books) }
                .onError { uiError -> state = state.copy(error = uiError) }
        }
    }
}
