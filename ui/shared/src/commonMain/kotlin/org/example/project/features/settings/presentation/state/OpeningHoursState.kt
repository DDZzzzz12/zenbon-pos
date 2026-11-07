package org.example.project.features.settings.presentation.state

import org.example.project.core.domain.dto.openinghours.OpeningHoursDto
import org.example.project.core.presentation.error.UiError

data class DaySlot(
    val openTime: String = "09:00",
    val closeTime: String = "22:00",
)

data class DayScheduleState(
    val isClosed: Boolean = false,
    val slots: List<DaySlot> = listOf(DaySlot()),
)

data class OpeningHoursState(
    val weeklySchedule: Map<Int, DayScheduleState> = (0..6).associateWith { DayScheduleState() },
    val overrides: List<OpeningHoursDto.OverrideResponseDto> = emptyList(),
    val editingOverride: OpeningHoursDto.OverrideResponseDto? = null,
    val showOverrideDialog: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: UiError? = null,
    val isScheduleLoaded: Boolean = false,
    /** What the server last returned (or accepted) for [weeklySchedule]; see [isScheduleDirty]. */
    val savedSchedule: Map<Int, DayScheduleState> = (0..6).associateWith { DayScheduleState() },
) {
    /** The weekly schedule draft differs from what was last loaded or saved. Edit-then-undo is clean. */
    val isScheduleDirty: Boolean get() = weeklySchedule != savedSchedule
}

sealed interface OpeningHoursEvent {
    data object LoadSchedule : OpeningHoursEvent

    data class ToggleDayClosed(
        val dayOfWeek: Int,
    ) : OpeningHoursEvent

    data class UpdateSlot(
        val dayOfWeek: Int,
        val index: Int,
        val openTime: String,
        val closeTime: String,
    ) : OpeningHoursEvent

    data class AddSlot(
        val dayOfWeek: Int,
    ) : OpeningHoursEvent

    data class RemoveSlot(
        val dayOfWeek: Int,
        val index: Int,
    ) : OpeningHoursEvent

    data object SaveSchedule : OpeningHoursEvent

    data object LoadOverrides : OpeningHoursEvent

    data class CreateOverride(
        val request: OpeningHoursDto.CreateOverrideRequestDto,
    ) : OpeningHoursEvent

    data class UpdateOverride(
        val id: Long,
        val request: OpeningHoursDto.UpdateOverrideRequestDto,
    ) : OpeningHoursEvent

    data class DeleteOverride(
        val id: Long,
    ) : OpeningHoursEvent

    data object ShowOverrideDialog : OpeningHoursEvent

    data object HideOverrideDialog : OpeningHoursEvent

    data class EditOverride(
        val override: OpeningHoursDto.OverrideResponseDto,
    ) : OpeningHoursEvent
}
