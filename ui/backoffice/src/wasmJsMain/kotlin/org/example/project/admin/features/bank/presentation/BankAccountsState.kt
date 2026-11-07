package org.example.project.admin.features.bank.presentation

import org.example.project.core.domain.dto.bank.BankAccountDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey

/**
 * [createError] is a field-level error for the create dialog and is deliberately separate from
 * [error]: a taken IBAN belongs next to the input that caused it, not in a global toast.
 *
 * [lastResolvedCount] is null until a resolve has run, so the screen can tell "not tried yet" from
 * "tried and found nothing".
 *
 * [statements] belong to [selectedAccountId] and to nothing else. They are fetched per account
 * rather than filtered out of [accounts], because the list endpoint carries no statements at all —
 * only a count and the ids of the breaks.
 */
data class BankAccountsState(
    val accounts: List<BankAccountDto.ResponseDto> = emptyList(),
    val unassigned: List<BankAccountDto.UnassignedStatementDto> = emptyList(),
    val selectedAccountId: Long? = null,
    val statements: List<BankAccountDto.StatementDto> = emptyList(),
    /**
     * Which statement is showing its bookings, or null when none is.
     *
     * One at a time: the bookings sit inside the statement table, and several open at once buries
     * the history they belong to.
     */
    val expandedStatementId: Long? = null,
    /**
     * Bookings already fetched, keyed by statement.
     *
     * Cached so collapsing and re-opening the same statement costs nothing. Cleared with
     * [statements] whenever the account changes — see [BankAccountsViewModel.selectAccount].
     */
    val transactionsByStatement: Map<Long, BankAccountDto.StatementTransactionListResponseDto> = emptyMap(),
    /** The statement whose bookings are in flight, so only the row being opened shows a skeleton. */
    val loadingTransactionsFor: Long? = null,
    val isLoading: Boolean = false,
    val isLoadingStatements: Boolean = false,
    val isBusy: Boolean = false,
    val lastResolvedCount: Int? = null,
    /**
     * Whether "Neues Bankkonto" is open. Owned here, not by the screen, so the success path can close
     * it: the screen used to infer "accepted" from an `isBusy` true→false transition inside a
     * `LaunchedEffect`, and a server answering within one frame never showed `isBusy = true` to
     * composition — the keys never changed, the effect never ran, and the dialog stayed open after
     * a successful create (E2E 2026-09-27).
     */
    val isCreateDialogOpen: Boolean = false,
    val createError: StringKey? = null,
    /** Bumped whenever the create dialog's Save is refused; the dialog focuses the IBAN on each bump. */
    val createAttempt: Int = 0,
    val error: UiError? = null,
) {
    val totalGaps: Int get() = accounts.sumOf { it.continuityBreaks.size }

    /** Null while nothing is selected, and also for a selection whose account has since gone. */
    val selectedAccount: BankAccountDto.ResponseDto?
        get() = accounts.firstOrNull { it.id == selectedAccountId }
}
