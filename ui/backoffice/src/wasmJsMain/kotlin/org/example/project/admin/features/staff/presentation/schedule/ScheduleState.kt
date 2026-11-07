package org.example.project.admin.features.staff.presentation.schedule

import org.example.project.core.domain.dto.employee.WorkScheduleDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey

sealed interface ScheduleState {
    data object Loading : ScheduleState

    data class Error(
        val error: UiError,
    ) : ScheduleState

    data class Data(
        val weekly: WorkScheduleDto.WeeklyScheduleResponseDto,
    ) : ScheduleState
}

/** One editable shift row (strings while editing). */
data class ShiftDraft(
    val startTime: String = "09:00",
    val endTime: String = "17:00",
    val breakDuration: String = "0",
)

/** Draft for editing a single day's shifts. */
data class DayEditor(
    val dayOfWeek: Int,
    val shifts: List<ShiftDraft>,
    val saving: Boolean = false,
    /** A server refusal — belongs to no field. Input refusals are [fieldErrors]. */
    val error: UiError? = null,
    /** Per-input validation refusals from the Save click, shown at the input. */
    val fieldErrors: Map<ShiftField, StringKey> = emptyMap(),
    /** Bumped on every Save click the validation refuses; the dialog reveals the first error on each bump. */
    val validationAttempt: Int = 0,
) {
    /** The shift rows differ from [original]'s; the transient save and validation flags are ignored. */
    fun isDirtyFrom(original: DayEditor?): Boolean = original != null && shifts != original.shifts
}
