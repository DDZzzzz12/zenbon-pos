package org.example.project.admin.features.salesinvoices.presentation

import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.presentation.error.UiError

/**
 * The buckets an operator asks this screen for.
 *
 * *Offen* / *Überfällig* arrived with the standalone invoices (4b): they need `open_amount > 0`,
 * which only the allocation gate (4b-2) can produce. Before that nothing could create an open AR
 * item, and an always-empty bucket teaches operators to ignore buckets.
 */
enum class SalesInvoiceBucket(
    val status: String?,
    val typeCode: String?,
    val open: Boolean = false,
    val overdue: Boolean = false,
) {
    /** Work in progress — the only state that can still be edited or deleted. */
    DRAFTS(status = "DRAFT", typeCode = null),

    /** Legal documents. `380` only, so a Gutschrift does not dilute the totals. */
    ISSUED(status = "ISSUED", typeCode = "380"),

    /**
     * Issued, `380`, open (`open_amount > 0`). Requires the AR allocation gate (4b-2) to exist —
     * before that nothing can produce an open AR item, which is why this bucket was absent at 4a.
     */
    OPEN(status = "ISSUED", typeCode = "380", open = true),

    /**
     * Issued, `380`, open and overdue (the due day is over: strictly before today).
     */
    OVERDUE(status = "ISSUED", typeCode = "380", open = true, overdue = true),

    /** A Gutschrift is not a sale, so it is counted apart from what was sold. */
    CREDIT_NOTES(status = null, typeCode = "381"),

    /** Originals a Gutschrift reversed — visible forever, never deleted. */
    CANCELLED(status = "CANCELLED", typeCode = null),
}

/** [bucket] is the *applied* bucket — what the last request used — so chips and list cannot disagree. */
data class SalesInvoicesState(
    val bucket: SalesInvoiceBucket = SalesInvoiceBucket.ISSUED,
    val items: List<SalesInvoiceDto.ListItemDto> = emptyList(),
    val total: Long = 0,
    /**
     * The server total of every bucket, for the Belegfluss strip and the next-step bar. Empty until
     * the first answer, which the strip draws as dashes rather than zeros.
     */
    val bucketCounts: Map<SalesInvoiceBucket, Long> = emptyMap(),
    val selected: SalesInvoiceDto.ResponseDto? = null,
    val isLoading: Boolean = false,
    val isMutating: Boolean = false,
    val error: UiError? = null,
) {
    /** Issuing is only ever valid on a draft — the server enforces it too (project rule 8). */
    val canIssueSelected: Boolean get() = selected?.status == "DRAFT" && !isMutating

    /** Only an ISSUED invoice can be cancelled; a draft is deleted, having consumed no number. */
    val canCancelSelected: Boolean get() = selected?.status == "ISSUED" && !isMutating

    /** A draft has no archived document, so there is nothing to open. */
    val canOpenPdf: Boolean get() = selected?.documentId != null
}
