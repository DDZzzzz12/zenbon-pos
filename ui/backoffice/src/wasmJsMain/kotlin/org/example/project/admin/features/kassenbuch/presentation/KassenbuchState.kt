package org.example.project.admin.features.kassenbuch.presentation

import kotlinx.datetime.LocalDate
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.presentation.error.UiError
import org.example.project.features.reports.presentation.ReportsGranularity

/**
 * UI state for the Kassenbuch screen: which cash book tab is open, the reporting period being
 * viewed, the entries fetched for it, and the optional global-search escape.
 *
 * [selectedBookId] `null` means the "Alle Kassen" tab — the period is then loaded without a
 * `cashBookId` filter, and no running balance column may be shown (no cross-book series exists).
 */
data class KassenbuchState(
    val books: List<CashBookDto.ResponseDto> = emptyList(),
    val selectedBookId: Long? = null,
    val granularity: ReportsGranularity = ReportsGranularity.MONTH,
    /** The date identifying the period shown; the ViewModel seeds it with today. */
    val anchor: LocalDate,
    val entries: List<KassenbuchDto.KassenbuchGroupedSummaryDto> = emptyList(),
    val filterText: String = "",
    /**
     * `null` means "not in global search mode" — deliberately distinct from an empty list, which
     * means "searched every period and found nothing". Conflating them leaves the empty state
     * unable to tell the operator which of the two happened.
     */
    val globalResults: List<KassenbuchDto.KassenbuchGroupedSummaryDto>? = null,
    /**
     * True when [globalResults] hit the server's hard cap. There is no continuation token, so a
     * truncated list would otherwise read to the operator as "that is everything".
     */
    val globalResultsTruncated: Boolean = false,
    val selectedEntry: KassenbuchDto.KassenbuchGroupedDetailDto? = null,
    /** The open Kassensturz dialog, or `null` when it is closed. Only ever set for a non-fiscal book. */
    val kassensturz: KassensturzUiState? = null,
    /**
     * The open "+ Buchung" form, or `null` when it is closed. Like [kassensturz] this is only ever
     * set for a non-fiscal book: fiscal entries need a signing terminal and are made at the till.
     */
    val entryForm: KassenbuchEntryFormState? = null,
    /** A backdating override went to a manager (202): nothing is booked yet. Shown once, then cleared. */
    val lateBookingAwaitingApproval: Boolean = false,
    /**
     * End time of the shop's most recent Z-Bon, shown read-only on the fiscal tab. `null` means
     * either "no Z-Bon yet" or "not fetched" — the display treats both the same way, so the two are
     * not worth distinguishing: it is fetched on every selection of the fiscal tab.
     */
    val latestZBonEndTime: String? = null,
    val isLoading: Boolean = false,
    /**
     * Whether the period in [anchor]/[granularity] has been fetched at least once. False until the
     * first load completes and again from the moment a new load starts, so "no entries" is only ever
     * claimed about a period that was actually asked for — not about the empty list the state starts with.
     */
    val hasLoaded: Boolean = false,
    val error: UiError? = null,
) {
    /**
     * [entries] narrowed by [filterText] against Belegnummer and Belegtext, case-insensitively.
     * Purely client-side over the loaded period — typing never issues a request. Global search is
     * the separate, explicit escape and is NOT folded in here.
     */
    val visibleEntries: List<KassenbuchDto.KassenbuchGroupedSummaryDto>
        get() {
            val needle = filterText.trim()
            if (needle.isEmpty()) return entries
            return entries.filter {
                it.belegNr.contains(needle, ignoreCase = true) || it.belegtext.contains(needle, ignoreCase = true)
            }
        }

    /** The book behind [selectedBookId]; `null` on the "Alle Kassen" tab or before the books load. */
    val selectedBook: CashBookDto.ResponseDto?
        get() = books.firstOrNull { it.id == selectedBookId }
}
