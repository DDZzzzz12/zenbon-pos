package org.example.project.admin.features.bank.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.remote.BankAccountApiClient
import org.example.project.core.domain.dto.bank.BankAccountDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.PosApiException
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.error.toUiError

/**
 * Drives the Bank tab: our own accounts, the statements whose IBAN matched none of them, and the
 * continuity gaps.
 *
 * Every action ends in [load] rather than adjusting the local lists: the server decides what is
 * still unassigned, so a locally-mutated list drifts from it.
 */
class BankAccountsViewModel(
    private val api: BankAccountApiClient,
) : ViewModel() {
    private val log = Logger.withTag("bank")

    var state by mutableStateOf(BankAccountsState())
        private set

    fun clearError() {
        state = state.copy(error = null)
    }

    fun openCreateDialog() {
        state = state.copy(isCreateDialogOpen = true, createError = null)
    }

    /** Closing drops a field error too, so reopening starts clean. */
    fun dismissCreateDialog() {
        state = state.copy(isCreateDialogOpen = false, createError = null)
    }

    /** Editing the IBAN drops its error: the operator is fixing exactly what was refused. */
    fun onCreateIbanEdited() {
        if (state.createError != null) state = state.copy(createError = null)
    }

    fun load() {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi { api.list() }
                .onSuccess { response ->
                    log.d { "bank accounts loaded: accounts=${response.items.size} unassigned=${response.unassigned.size}" }
                    val selected = resolveSelection(response.items, state.selectedAccountId)
                    state =
                        state.copy(
                            isLoading = false,
                            accounts = response.items,
                            unassigned = response.unassigned,
                            selectedAccountId = selected,
                        )
                    if (selected != null) fetchStatements(selected)
                }.onError(retry = { load() }) { uiError ->
                    log.e { "bank accounts load failed: $uiError" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }

    /**
     * Keeps the operator where they were looking across a reload, which every mutating action ends
     * in, and selects **nothing** otherwise: on arrival, and when the selected account is no longer
     * in the list (a deactivated account then leaves the "pick an account" prompt, never a pane
     * pointing at nothing).
     *
     * NEW-08: this used to fall back to the first account, which drew that row as selected while no
     * detail was open on a phone and in the drill band; the other split screens never auto-select.
     */
    private fun resolveSelection(
        accounts: List<BankAccountDto.ResponseDto>,
        current: Long?,
    ): Long? = accounts.firstOrNull { it.id == current }?.id

    /**
     * Shows one account's statements.
     *
     * The previous account's statements are cleared **synchronously**, before the fetch is even
     * dispatched: leaving them on screen under the newly selected account's name would attribute one
     * account's history to another, which is the one thing this screen must never do.
     */
    fun selectAccount(accountId: Long) {
        if (accountId == state.selectedAccountId) return
        state =
            state.copy(
                selectedAccountId = accountId,
                statements = emptyList(),
                isLoadingStatements = true,
                // The cached bookings are keyed by statement, and statement ids do not repeat across
                // accounts — but they are dropped for the same reason the statements are: what is on
                // screen must belong to the account named above it, with no window where it does not.
                expandedStatementId = null,
                transactionsByStatement = emptyMap(),
                loadingTransactionsFor = null,
            )
        viewModelScope.launch { fetchStatements(accountId) }
    }

    /**
     * Opens or closes one statement's bookings.
     *
     * Collapsing keeps what was fetched, so re-opening the same statement costs no request. Only a
     * statement never fetched dispatches one — an expansion is a read, and re-reading on every
     * toggle would make an operator comparing two statements pay for each glance.
     */
    fun toggleStatement(statementId: Long) {
        if (state.expandedStatementId == statementId) {
            state = state.copy(expandedStatementId = null)
            return
        }
        state = state.copy(expandedStatementId = statementId)
        if (state.transactionsByStatement.containsKey(statementId)) return
        // Already fetching this one. The cache is only written on success, so without this a second
        // dispatch before the first answers sees a miss and fires again — observed as three
        // identical GETs inside 94 ms from a single click on the row.
        if (state.loadingTransactionsFor == statementId) return
        val accountId = state.selectedAccountId ?: return
        state = state.copy(loadingTransactionsFor = statementId)
        viewModelScope.launch { fetchTransactions(accountId, statementId) }
    }

    private suspend fun fetchTransactions(
        accountId: Long,
        statementId: Long,
    ) {
        state = state.copy(loadingTransactionsFor = statementId)
        runCatchingApi { api.statementTransactions(id = accountId, statementId = statementId) }
            .onSuccess { response ->
                log.d { "statement $statementId bookings loaded: ${response.items.size} of ${response.total}" }
                // Same rule as the statement fetch: an answer for an account no longer selected is
                // stale, and caching it would show one account's bookings under another's name.
                if (state.selectedAccountId != accountId) return@onSuccess
                state =
                    state.copy(
                        loadingTransactionsFor = null,
                        transactionsByStatement = state.transactionsByStatement + (statementId to response),
                    )
            }.onError(retry = { retryTransactions(accountId, statementId) }) { uiError ->
                log.e { "statement $statementId bookings load failed: $uiError" }
                // The row collapses on failure rather than staying open over nothing: an expanded row
                // showing neither bookings nor a spinner reads as "this statement is empty".
                state = state.copy(loadingTransactionsFor = null, expandedStatementId = null, error = uiError)
            }
    }

    private fun retryTransactions(
        accountId: Long,
        statementId: Long,
    ) {
        state = state.copy(expandedStatementId = statementId, loadingTransactionsFor = statementId)
        viewModelScope.launch { fetchTransactions(accountId, statementId) }
    }

    private suspend fun fetchStatements(accountId: Long) {
        state = state.copy(isLoadingStatements = true)
        runCatchingApi { api.statements(accountId) }
            .onSuccess { response ->
                log.d { "account $accountId statements loaded: ${response.items.size}" }
                // Discard a response for an account the operator has already navigated away from —
                // two clicks in quick succession would otherwise let the slower answer win.
                if (state.selectedAccountId != accountId) return@onSuccess
                state = state.copy(isLoadingStatements = false, statements = response.items)
            }.onError(retry = { selectAccountAgain(accountId) }) { uiError ->
                log.e { "account $accountId statements load failed: $uiError" }
                state = state.copy(isLoadingStatements = false, error = uiError)
            }
    }

    /** The retry path for a failed statement fetch — [selectAccount] would no-op on the same id. */
    private fun selectAccountAgain(accountId: Long) {
        viewModelScope.launch { fetchStatements(accountId) }
    }

    /**
     * Validates first and **returns without dispatching** when the IBAN is blank — an account is
     * identified by its IBAN, so an empty one has nothing to match statements against.
     */
    fun createAccount(request: BankAccountDto.CreateRequestDto) {
        if (request.iban.isBlank()) {
            log.w { "bank account create blocked: IBAN is blank" }
            state = state.copy(createError = AdminStringKeys.BankErrorIbanRequired, createAttempt = state.createAttempt + 1)
            return
        }
        state = state.copy(isBusy = true, createError = null, error = null)
        viewModelScope.launch { dispatchCreate(request) }
    }

    /**
     * A `try/catch` rather than [runCatchingApi] because a 409 carrying
     * [PosErrorCode.BANK_ACCOUNT_IBAN_TAKEN] is actionable next to the input — the operator already
     * has that account — and must not collapse into the generic "an unknown error occurred".
     */
    private suspend fun dispatchCreate(request: BankAccountDto.CreateRequestDto) {
        try {
            val created = api.create(request)
            log.d { "bank account ${created.id} created" }
            // The dialog closes only once the server accepted: a taken IBAN keeps it open with
            // createError next to the input the operator has to change.
            state = state.copy(isBusy = false, isCreateDialogOpen = false)
            load()
        } catch (e: PosApiException) {
            if (e.code == PosErrorCode.BANK_ACCOUNT_IBAN_TAKEN) {
                log.w { "bank account create rejected: IBAN already belongs to an account" }
                state =
                    state.copy(isBusy = false, createError = AdminStringKeys.BankErrorIbanTaken, createAttempt = state.createAttempt + 1)
            } else {
                log.e { "bank account create failed: $e" }
                failAction(e.toUiError())
            }
        } catch (e: NetworkApiException) {
            log.e(e) { "bank account create failed: transport error" }
            failAction(e.toUiError())
        }
    }

    fun assignStatement(
        statementId: Long,
        bankAccountId: Long,
    ) {
        state = state.copy(isBusy = true, error = null)
        viewModelScope.launch {
            runCatchingApi {
                api.assignStatement(statementId, BankAccountDto.AssignStatementRequestDto(bankAccountId))
            }.onSuccess {
                log.d { "statement $statementId assigned to account $bankAccountId" }
                state = state.copy(isBusy = false)
                load()
            }.onError { uiError ->
                log.e { "statement $statementId assignment failed: $uiError" }
                failAction(uiError)
            }
        }
    }

    fun resolveUnassigned() {
        state = state.copy(isBusy = true, error = null)
        viewModelScope.launch {
            runCatchingApi { api.resolveUnassigned() }
                .onSuccess { response ->
                    log.d { "resolution sweep claimed ${response.resolved} statement(s)" }
                    state = state.copy(isBusy = false, lastResolvedCount = response.resolved)
                    load()
                }.onError { uiError ->
                    log.e { "resolution sweep failed: $uiError" }
                    failAction(uiError)
                }
        }
    }

    private fun failAction(uiError: UiError) {
        state = state.copy(isBusy = false, error = uiError)
    }
}
