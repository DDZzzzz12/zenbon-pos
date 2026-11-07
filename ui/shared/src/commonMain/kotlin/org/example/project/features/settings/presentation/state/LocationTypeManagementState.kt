package org.example.project.features.settings.presentation.state

import org.example.project.core.domain.dto.table.LocationTypeDto
import org.example.project.core.presentation.error.UiError

/**
 * Location type management state.
 */
data class LocationTypeManagementState(
    val locationTypes: List<LocationTypeDto.ResponseDto> = emptyList(),
    val isLoading: Boolean = false,
    val showAddDialog: Boolean = false,
    val editingLocationType: LocationTypeDto.ResponseDto? = null,
    /** A create/update from the add/edit dialog is in flight: its Save shows progress and ignores presses. */
    val isSaving: Boolean = false,
    val error: UiError? = null,
)
