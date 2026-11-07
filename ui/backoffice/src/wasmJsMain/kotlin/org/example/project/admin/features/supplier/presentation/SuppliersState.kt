package org.example.project.admin.features.supplier.presentation

import org.example.project.core.domain.dto.supplier.SupplierDto
import org.example.project.core.presentation.error.UiError

/**
 * [provisionalOnly], [sort] and [sortAscending] are the *applied* filter — what the last request
 * used — so the chips, the header arrow and the list cannot disagree.
 *
 * [search] is the exception: it is the *typed* value, echoed to the field on every keystroke while
 * the request is debounced behind it. Binding the field to the applied term instead would drop the
 * characters typed while a request was in flight.
 */
data class SuppliersState(
    val items: List<SupplierDto.ResponseDto> = emptyList(),
    val total: Long = 0,
    val provisionalOnly: Boolean = false,
    val search: String = "",
    val sort: SupplierSortColumn = SupplierSortColumn.NAME,
    val sortAscending: Boolean = true,
    val isLoading: Boolean = false,
    val error: UiError? = null,
) {
    /** Whether anything is narrowing the list — decides which empty state, and whether to offer a reset. */
    val hasActiveFilter: Boolean get() = search.isNotBlank() || provisionalOnly
}
