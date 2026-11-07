package org.example.project.admin.features.debtors.presentation

import org.example.project.core.domain.dto.debtor.DebtorDto
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.presentation.error.UiError

/**
 * [activeOnly] is the applied filter, not a draft: it is what the last request used, so the chip row
 * and the list cannot disagree.
 *
 * [search] is the exception: it is the *typed* value, echoed to the field on every keystroke while
 * the request is debounced behind it. Binding the field to the applied term instead would drop the
 * characters typed while a request was in flight.
 *
 * [invoices] belong to [selectedDebtorId] and to nothing else. They are fetched per debtor through
 * the sales-invoice list's `debtorId` filter rather than narrowed from a page of all invoices —
 * that list is paged newest-first, so a debtor whose last invoice fell outside the page would show
 * as having none.
 */
data class DebtorsState(
    val items: List<DebtorDto.ResponseDto> = emptyList(),
    val total: Long = 0,
    val activeOnly: Boolean = false,
    val search: String = "",
    val selectedDebtorId: Long? = null,
    val invoices: List<SalesInvoiceDto.ListItemDto> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingInvoices: Boolean = false,
    val error: UiError? = null,
) {
    /** Whether anything is narrowing the list — decides the empty state and whether to offer a reset. */
    val hasActiveFilter: Boolean get() = search.isNotBlank() || activeOnly

    /** Null while nothing is selected, and also for a selection the current filter has removed. */
    val selectedDebtor: DebtorDto.ResponseDto?
        get() = items.firstOrNull { it.id == selectedDebtorId }
}
