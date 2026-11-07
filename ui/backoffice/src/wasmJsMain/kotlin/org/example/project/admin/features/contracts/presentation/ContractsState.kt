package org.example.project.admin.features.contracts.presentation

import org.example.project.core.domain.dto.contract.ContractAlertDto
import org.example.project.core.domain.dto.contract.ContractDto
import org.example.project.core.presentation.error.UiError

/**
 * [includeInactive] is the applied flag, not a draft: it is what the last request used, so the
 * toggle and the list cannot disagree.
 *
 * [search] is the exception: it is the *typed* value, echoed to the field on every keystroke while
 * the request is debounced behind it.
 *
 * [alerts] is every active contract's alerts, not this page's: the summary line answers "does
 * anything need me at all", which a per-row count cannot. The per-row figure comes from
 * `ResponseDto.alertCount` instead, so the two never need to be reconciled here.
 */
data class ContractsState(
    val items: List<ContractDto.ResponseDto> = emptyList(),
    val total: Long = 0,
    val alerts: List<ContractAlertDto.AlertDto> = emptyList(),
    val includeInactive: Boolean = false,
    val search: String = "",
    val isLoading: Boolean = false,
    val error: UiError? = null,
) {
    val alertCount: Int get() = alerts.size

    /** Whether anything is narrowing the list — decides the empty state and whether to offer a reset. */
    val hasActiveFilter: Boolean get() = search.isNotBlank() || includeInactive
}
