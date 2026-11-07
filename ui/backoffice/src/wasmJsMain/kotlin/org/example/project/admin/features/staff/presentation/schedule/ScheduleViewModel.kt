package org.example.project.admin.features.staff.presentation.schedule

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.WorkScheduleApiClient
import org.example.project.core.domain.dto.employee.WorkScheduleDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi

class ScheduleViewModel(
    private val scheduleApi: WorkScheduleApiClient,
) : ViewModel() {
    private val log = Logger.withTag("schedule")
    private var employeeId: String? = null

    var state by mutableStateOf<ScheduleState>(ScheduleState.Loading)
        private set

    var dayEditor by mutableStateOf<DayEditor?>(null)
        private set

    /** The day editor as it was opened, to tell an edited form from an untouched one. */
    private var dayEditorOriginal: DayEditor? = null

    /** True while the open day editor differs from how it was opened. Read by the leave guard. */
    val dayEditorDirty: Boolean get() = dayEditor?.isDirtyFrom(dayEditorOriginal) == true

    fun load(employeeId: String) {
        this.employeeId = employeeId
        dayEditor = null
        dayEditorOriginal = null
        state = ScheduleState.Loading
        viewModelScope.launch {
            runCatchingApi { scheduleApi.getWeeklySchedule(employeeId) }
                .onSuccess { schedule -> state = ScheduleState.Data(schedule) }
                .onError { uiError ->
                    log.e { "load schedule failed" }
                    state = ScheduleState.Error(uiError)
                }
        }
    }

    fun openEditDay(dayOfWeek: Int) {
        val weekly = (state as? ScheduleState.Data)?.weekly ?: return
        val shifts =
            weekly.days
                .firstOrNull { it.dayOfWeek == dayOfWeek }
                ?.shifts
                .orEmpty()
                .map { ShiftDraft(it.startTime, it.endTime, it.breakDuration.toString()) }
        val opened = DayEditor(dayOfWeek = dayOfWeek, shifts = shifts)
        dayEditorOriginal = opened
        dayEditor = opened
    }

    fun closeDay() {
        dayEditor = null
        dayEditorOriginal = null
    }

    /** Adding or removing a row renumbers the rows below it, so their field errors are dropped. */
    fun addShift() {
        dayEditor = dayEditor?.let { it.copy(shifts = it.shifts + ShiftDraft(), error = null, fieldErrors = emptyMap()) }
    }

    fun removeShift(index: Int) {
        dayEditor =
            dayEditor?.let {
                it.copy(shifts = it.shifts.filterIndexed { i, _ -> i != index }, error = null, fieldErrors = emptyMap())
            }
    }

    fun onShift(
        index: Int,
        block: (ShiftDraft) -> ShiftDraft,
    ) {
        val e = dayEditor ?: return
        val previous = e.shifts.getOrNull(index) ?: return
        dayEditor =
            e
                .copy(shifts = e.shifts.mapIndexed { i, s -> if (i == index) block(s) else s }, error = null)
                .withShiftErrorsCleared(index, previous)
    }

    /** Only called once [validateDayEditor] passed, so every row parses. */
    private fun toShiftDtos(e: DayEditor): List<WorkScheduleDto.ShiftDto> =
        e.shifts.map { d ->
            WorkScheduleDto.ShiftDto(startTime = d.startTime, endTime = d.endTime, breakDuration = d.breakMinutesOrNull() ?: 0)
        }

    fun saveDay() {
        val e = dayEditor ?: return
        val id = employeeId ?: return
        val errors = validateDayEditor(e)
        if (errors.isNotEmpty()) {
            dayEditor = e.copy(fieldErrors = errors, validationAttempt = e.validationAttempt + 1, error = null)
            return
        }
        val dtos = toShiftDtos(e)
        dayEditor = e.copy(saving = true, error = null, fieldErrors = emptyMap())
        viewModelScope.launch {
            runCatchingApi {
                scheduleApi.updateDaySchedule(
                    id,
                    WorkScheduleDto.UpdateDayRequestDto(dayOfWeek = e.dayOfWeek, shifts = dtos),
                )
            }.onSuccess { updated ->
                log.d { "schedule day ${e.dayOfWeek} updated for $id" }
                state = ScheduleState.Data(updated)
                dayEditor = null
            }.onError { uiError ->
                log.e { "save schedule day failed" }
                dayEditor = dayEditor?.copy(saving = false, error = uiError)
            }
        }
    }
}
