package org.example.project.features.reservation.presentation.state

import org.example.project.core.domain.dto.reservation.ReservationDto
import org.example.project.core.presentation.error.UiError

data class TableAssignmentState(
    val reservation: ReservationDto.ResponseDto? = null,
    val assignments: List<ReservationDto.AssignmentDto> = emptyList(),
    val availableTables: List<ReservationDto.AvailableTableDto> = emptyList(),
    val selectedGroup: Int = 1,
    val selectedTableIds: Set<String> = emptySet(),
    val isManualExpanded: Boolean = false,
    val showUnassignConfirmDialog: Boolean = false,
    val isLoading: Boolean = false,
    val error: UiError? = null,
)

sealed interface TableAssignmentEvent {
    data class Open(
        val reservation: ReservationDto.ResponseDto,
    ) : TableAssignmentEvent

    data object Close : TableAssignmentEvent

    data object AutoAssign : TableAssignmentEvent

    data object ManualAssign : TableAssignmentEvent

    data object RequestUnassignAll : TableAssignmentEvent

    data object ConfirmUnassignAll : TableAssignmentEvent

    data object DismissUnassignConfirm : TableAssignmentEvent

    data class SelectGroup(
        val groupNumber: Int,
    ) : TableAssignmentEvent

    data object AddGroup : TableAssignmentEvent

    data class ToggleTable(
        val tableId: String,
    ) : TableAssignmentEvent

    data object ToggleManualSection : TableAssignmentEvent
}
