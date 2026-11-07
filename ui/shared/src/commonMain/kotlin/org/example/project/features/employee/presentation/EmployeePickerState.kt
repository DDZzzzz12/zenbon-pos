package org.example.project.features.employee.presentation

import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.presentation.error.UiError

/**
 * Roster state for [EmployeePicker]. Deliberately carries no selected id: the picker's selection is
 * hoisted to the caller, because one ViewModel instance is shared by every picker in the app.
 */
data class EmployeePickerState(
    val employees: List<EmployeeDto.EmployeeSummaryResponseDto> = emptyList(),
    val isLoading: Boolean = false,
    val error: UiError? = null,
    /** True once a load has completed, so re-entering a screen does not refetch. */
    val loaded: Boolean = false,
    /**
     * The `activeOnly` value the current [employees] roster was fetched with. Compared against the
     * caller's requested value on the next `load()` call so a filter change (or the initial load)
     * always refetches, while a repeat call with the SAME value still short-circuits.
     */
    val loadedActiveOnly: Boolean? = null,
)
