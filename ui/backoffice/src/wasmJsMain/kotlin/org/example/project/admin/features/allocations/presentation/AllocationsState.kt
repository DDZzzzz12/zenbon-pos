package org.example.project.admin.features.allocations.presentation

import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.domain.dto.allocation.SuggestionDto
import org.example.project.core.domain.dto.bank.BankAccountDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.presentation.error.UiError

/**
 * [filter] is the **applied** filter — what the last request used — so the chips and the list
 * cannot disagree.
 *
 * [amountDrafts] is keyed by `draftKey(targetKind, targetId)` — never by a bare id, which would
 * share one amount between invoice 7 and instalment 7 — and lives only while a row is expanded;
 * collapsing clears it, so a number typed against one payment can never be submitted against the
 * next.
 *
 * [suggestions] belongs to the expanded row alone and is cleared whenever that row changes, for the
 * same reason: a suggestion computed for the previous payment must never be acceptable against the
 * next one. An empty list is the normal answer, not a failure.
 *
 * The Buchungsbeginn is deliberately absent here: it is shown on Rechnungen as well, so its state
 * lives in `BookkeepingStartController` rather than in either screen's own state.
 */
data class AllocationsState(
    val filter: AllocationDto.QueueFilter = AllocationDto.QueueFilter.NICHT_ZUGEORDNET,
    val rows: List<AllocationDto.QueueRowDto> = emptyList(),
    val total: Long = 0,
    val expandedTransactionId: Long? = null,
    val openInvoices: List<AllocationDto.OpenInvoiceDto> = emptyList(),
    /**
     * The case-C (STANDALONE) invoice candidates — the AR panel of the editor. Loaded on demand like
     * the instalments: most payments settle an AP invoice, and fetching the AR list for every
     * expanded row would spend a round trip on a list nobody looks at.
     */
    val openSalesInvoices: List<AllocationDto.OpenSalesInvoiceDto> = emptyList(),
    /**
     * The instalment candidates, loaded only once the operator asks for that kind — most payments
     * settle an invoice, and fetching a contract schedule for every expanded row would spend a round
     * trip on a list nobody looks at.
     */
    val openInstalments: List<AllocationDto.OpenInstalmentDto> = emptyList(),
    /** Our own bank accounts — the counter-leg candidates of an Eigenübertrag. Loaded on demand. */
    val bankAccounts: List<BankAccountDto.ResponseDto> = emptyList(),
    /**
     * The recent Kassenbuch entries — the counter-leg candidates of a Geldtransit. Loaded on demand,
     * and deliberately **unfiltered**: which entry pairs with the deposit is the operator's reading
     * of direction, amount and date, and a type filter would hide a legitimate one while pretending
     * to be that judgement.
     */
    val cashEntries: List<KassenbuchDto.KassenbuchGroupedSummaryDto> = emptyList(),
    /**
     * The counter-leg the operator picked, for the two kinds that need one. Never defaulted to the
     * first candidate: a preselected counter-leg is a booking nobody chose.
     */
    val selectedCounterId: Long? = null,
    val amountDrafts: Map<String, String> = emptyMap(),
    /**
     * Which target kind the expanded editor is offering. Reset to INVOICE whenever a row expands —
     * carrying the previous row's kind over would silently point a new payment at the wrong sort of
     * target.
     */
    val targetKind: String = "INVOICE",
    val bookingTextDraft: String = "",
    val accountNoDraft: String = "",
    val suggestions: List<SuggestionDto.ItemDto> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: UiError? = null,
) {
    /**
     * The expanded editor holds something the operator entered or chose: a typed amount, a booking
     * text, an account number or a counter-leg. This is the part of what `clearedEditor()` resets
     * that is user input; the loaded candidate lists and suggestions are not.
     *
     * [targetKind] is deliberately **not** counted. Switching the kind tab is a view toggle that
     * changes no draft (a kind switch itself clears them), so leaving after only looking at another
     * tab loses nothing and must not prompt.
     */
    val editorDirty: Boolean
        get() =
            amountDrafts.values.any { it.isNotBlank() } ||
                bookingTextDraft.isNotBlank() ||
                accountNoDraft.isNotBlank() ||
                selectedCounterId != null
}
