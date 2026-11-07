/**
 * State for Kassenbuch (cash book) feature.
 *
 * Encapsulates all state related to the kassenbuch feature
 * including visibility, data, loading, filters, date range, and selected entries.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.financialdocuments.presentation.state

import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.state.DateRange

/**
 * State for Kassenbuch (cash book) panel.
 *
 * Represents the current state of the kassenbuch panel including:
 * - Visibility state
 * - List of entries
 * - Current balance
 * - Selected entry details
 * - Loading states
 * - Filters and date ranges
 *
 * @property isVisible Whether the kassenbuch panel is visible
 * @property entries List of kassenbuch entries grouped by belegNr for list display
 * @property currentBalance Current cash balance as formatted string
 * @property selectedId Selected grouped summary for detail view (null if none selected)
 * @property selectedEntryDetail Full detail DTO fetched from server (null if not loaded)
 * @property isLoadingDetails Whether entry details are currently loading
 * @property showCreateDialog Whether create entry dialog is shown
 * @property isLoading Whether entries are currently loading
 * @property error Typed error for entry loading failure (null if no error)
 * @property selectedDateRange Selected date range filter
 * @property customDateRange Custom date range (used when selectedDateRange is CUSTOM)
 * @property showLinkedPdfViewer Whether to show linked PDF viewer
 */
data class KassenbuchState(
    /** Whether the kassenbuch panel is visible */
    val isVisible: Boolean = false,
    /** List of kassenbuch entries grouped by belegNr for list display */
    val entries: List<KassenbuchDto.KassenbuchGroupedSummaryDto> = emptyList(),
    /** Current cash balance as display text ("5.524,72"), formatted from the server's decimal */
    val currentBalance: String = "0,00",
    /** Full detail DTO fetched from server (null if not loaded) */
    val selectedEntryDetail: KassenbuchDto.KassenbuchGroupedDetailDto? = null,
    /** Whether entry details are currently loading */
    val isLoadingDetails: Boolean = false,
    /** Whether create entry dialog is shown */
    val showCreateDialog: Boolean = false,
    /**
     * F59: what the fiscal Ladenkasse can pay out right now (book + cash takings no Z-Bon has closed
     * yet), loaded when the create form opens — the form blocks a withdrawal above it. Null while
     * unknown; the server's Kassenminus guard refuses regardless.
     */
    val availableCash: String? = null,
    /** Whether the manual cash transfer (Geldtransit) form is shown */
    val showTransferDialog: Boolean = false,
    /**
     * The shop's cash books — loaded with every Kassenbuch refresh (drives the book selector) and
     * again when the transfer form opens (drives its pickers).
     */
    val cashBooks: List<CashBookDto.ResponseDto> = emptyList(),
    /**
     * The one book the list and all three summary cards are scoped to. `null` until the books
     * first load, after which it is the fiscal Ladenkasse unless the operator picked another.
     *
     * There is deliberately no "all books" value: Kassenbestand is a per-book figure the server
     * never sums, so a mixed list beside it produces Einnahmen/Ausgaben that cannot reconcile.
     */
    val selectedCashBookId: Long? = null,
    /** Whether a transfer is in flight; blocks a double submit */
    val isTransferring: Boolean = false,
    /** Whether entry creation (+ optional PDF upload/link) is in progress */
    val isCreating: Boolean = false,
    /**
     * A backdating override went to a manager (202): nothing is booked yet. The screen says so once
     * and clears it — "sent for approval" must not read like "booked".
     */
    val lateBookingAwaitingApproval: Boolean = false,
    /** Whether entries are currently loading */
    val isLoading: Boolean = false,
    /** Typed error for entry loading failure (null if no error) */
    val error: UiError? = null,
    /** Selected date range filter */
    val selectedDateRange: DateRange = DateRange.THIS_WEEK,
    /** Custom date range (used when selectedDateRange is CUSTOM) */
    val customDateRange: Pair<String, String>? = null,
    /** Total income (sum of incoming entries), signed display string ("+150,00"; a zero is "0,00") */
    val totalIncome: String = "0,00",
    /** Total expenses (sum of outgoing entries), signed display string ("-50,00"; a zero is "0,00") */
    val totalExpenses: String = "0,00",
    /** Whether to show linked PDF viewer */
    val showLinkedPdfViewer: Boolean = false,
    /** Selected linked document ID for PDF preview (null if none selected) */
    val selectedLinkedDocumentId: Long? = null,
    /** Header ID pending auto-link after document upload (null if no pending link) */
    val pendingLinkAfterUpload: Long? = null,
    /** Header ID pending auto-replace after document upload (null if no pending replace) */
    val pendingReplaceAfterUpload: Long? = null,
    /** Whether the correction (Storno) dialog is shown */
    val showCorrectionDialog: Boolean = false,
    /** The entry being corrected (null if no correction dialog open) */
    val correctionTarget: KassenbuchDto.KassenbuchGroupedDetailDto? = null,
    /** Whether a correction submission is in progress */
    val isCorrecting: Boolean = false,
) {
    /** Active books in display order — what the selector offers. Shown only when there are two or more. */
    val selectableCashBooks: List<CashBookDto.ResponseDto>
        get() = cashBooks.filter { it.isActive }.sortedWith(compareBy({ it.sortOrder }, { it.name }))
}
