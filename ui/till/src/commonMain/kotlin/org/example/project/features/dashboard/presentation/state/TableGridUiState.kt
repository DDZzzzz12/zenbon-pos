package org.example.project.features.dashboard.presentation.state

import org.example.project.core.data.models.RTable
import org.example.project.core.domain.constants.WellKnownLocationTypes
import org.example.project.core.domain.dto.table.LocationTypeDto
import org.example.project.core.presentation.error.UiError

/**
 * UI state for Dashboard screen.
 *
 * @property isLoading Whether tables are currently being loaded
 * @property tablesByType Map of table types to their corresponding tables
 * @property selectedLocationTypeId Currently selected location type ID tab
 * @property locationTypes Available non-virtual location types fetched from API
 * @property error Typed error if loading failed (for global notification system)
 */
data class TableGridUiState(
    val isLoading: Boolean = true,
    val tablesByType: List<RTable> = emptyList(),
    val selectedLocationTypeId: Long = WellKnownLocationTypes.INDOOR,
    val locationTypes: List<LocationTypeDto.ResponseDto> = emptyList(),
    val showInactive: Boolean = false,
    val error: UiError? = null,
)
