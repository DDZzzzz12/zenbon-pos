@file:OptIn(kotlin.time.ExperimentalTime::class)

package org.example.project.features.settings.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.dto.openinghours.OpeningHoursDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.features.openinghours.data.remote.OpeningHoursApiClient
import org.example.project.features.settings.presentation.state.DayScheduleState
import org.example.project.features.settings.presentation.state.DaySlot
import org.example.project.features.settings.presentation.state.OpeningHoursEvent
import org.example.project.features.settings.presentation.state.OpeningHoursState
import org.example.project.features.settings.presentation.state.hasInvalidSlot
import org.example.project.features.settings.presentation.state.overrideHoursAreValid
import kotlin.time.Clock

class OpeningHoursViewModel(
    private val openingHoursApiClient: OpeningHoursApiClient,
) : ViewModel() {
    private val logger = Logger.withTag("OpeningHoursViewModel")

    private val _uiState = MutableStateFlow(OpeningHoursState())
    val uiState: StateFlow<OpeningHoursState> = _uiState.asStateFlow()

    fun onEvent(event: OpeningHoursEvent) {
        when (event) {
            is OpeningHoursEvent.LoadSchedule -> loadSchedule()
            is OpeningHoursEvent.ToggleDayClosed -> toggleDayClosed(event.dayOfWeek)
            is OpeningHoursEvent.UpdateSlot -> updateSlot(event.dayOfWeek, event.index, event.openTime, event.closeTime)
            is OpeningHoursEvent.AddSlot -> addSlot(event.dayOfWeek)
            is OpeningHoursEvent.RemoveSlot -> removeSlot(event.dayOfWeek, event.index)
            is OpeningHoursEvent.SaveSchedule -> saveSchedule()
            is OpeningHoursEvent.LoadOverrides -> loadOverrides()
            is OpeningHoursEvent.CreateOverride -> createOverride(event.request)
            is OpeningHoursEvent.UpdateOverride -> updateOverride(event.id, event.request)
            is OpeningHoursEvent.DeleteOverride -> deleteOverride(event.id)
            is OpeningHoursEvent.ShowOverrideDialog -> _uiState.update { it.copy(showOverrideDialog = true, editingOverride = null) }
            is OpeningHoursEvent.HideOverrideDialog -> _uiState.update { it.copy(showOverrideDialog = false, editingOverride = null) }
            is OpeningHoursEvent.EditOverride -> _uiState.update { it.copy(showOverrideDialog = true, editingOverride = event.override) }
        }
    }

    private fun loadSchedule() {
        logger.i { "OpeningHoursViewModel.loadSchedule" }
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            runCatchingApi { openingHoursApiClient.getAll() }
                .onSuccess { entries ->
                    val grouped = entries.groupBy { it.dayOfWeek }
                    val schedule =
                        (0..6).associateWith { day ->
                            val daySlots = grouped[day] ?: emptyList()
                            if (daySlots.isEmpty()) {
                                DayScheduleState()
                            } else {
                                DayScheduleState(
                                    isClosed = daySlots.all { it.isClosed },
                                    slots =
                                        daySlots
                                            .filter { !it.isClosed }
                                            .map { DaySlot(it.openTime, it.closeTime) }
                                            .ifEmpty { listOf(DaySlot()) },
                                )
                            }
                        }
                    _uiState.update {
                        it.copy(
                            weeklySchedule = schedule,
                            savedSchedule = schedule,
                            isLoading = false,
                            isScheduleLoaded = true,
                        )
                    }
                }.onError { uiError ->
                    logger.e { "OpeningHoursViewModel.loadSchedule: failed" }
                    _uiState.update { it.copy(isLoading = false, error = uiError) }
                }
        }
    }

    private fun toggleDayClosed(dayOfWeek: Int) {
        _uiState.update { state ->
            val schedule = state.weeklySchedule.toMutableMap()
            val day = schedule[dayOfWeek] ?: DayScheduleState()
            schedule[dayOfWeek] = day.copy(isClosed = !day.isClosed)
            state.copy(weeklySchedule = schedule)
        }
    }

    private fun updateSlot(
        dayOfWeek: Int,
        index: Int,
        openTime: String,
        closeTime: String,
    ) {
        _uiState.update { state ->
            val schedule = state.weeklySchedule.toMutableMap()
            val day = schedule[dayOfWeek] ?: return@update state
            val slots = day.slots.toMutableList()
            if (index in slots.indices) {
                slots[index] = DaySlot(openTime, closeTime)
                schedule[dayOfWeek] = day.copy(slots = slots)
            }
            state.copy(weeklySchedule = schedule)
        }
    }

    private fun addSlot(dayOfWeek: Int) {
        _uiState.update { state ->
            val schedule = state.weeklySchedule.toMutableMap()
            val day = schedule[dayOfWeek] ?: DayScheduleState()
            schedule[dayOfWeek] = day.copy(slots = day.slots + DaySlot())
            state.copy(weeklySchedule = schedule)
        }
    }

    private fun removeSlot(
        dayOfWeek: Int,
        index: Int,
    ) {
        _uiState.update { state ->
            val schedule = state.weeklySchedule.toMutableMap()
            val day = schedule[dayOfWeek] ?: return@update state
            val slots = day.slots.toMutableList()
            if (index in slots.indices && slots.size > 1) {
                slots.removeAt(index)
                schedule[dayOfWeek] = day.copy(slots = slots)
            }
            state.copy(weeklySchedule = schedule)
        }
    }

    private fun saveSchedule() {
        logger.i { "OpeningHoursViewModel.saveSchedule" }
        if (_uiState.value.weeklySchedule.hasInvalidSlot()) {
            // The editor already disables the button and marks the slot; this is the VM-level guard.
            logger.w { "OpeningHoursViewModel.saveSchedule: refused, a slot opens at or after it closes" }
            return
        }
        _uiState.update { it.copy(isSaving = true, error = null) }

        val days =
            _uiState.value.weeklySchedule.flatMap { (dayOfWeek, dayState) ->
                if (dayState.isClosed) {
                    listOf(
                        OpeningHoursDto.DaySchedule(
                            dayOfWeek = dayOfWeek,
                            openTime = "00:00",
                            closeTime = "23:59",
                            isClosed = true,
                        ),
                    )
                } else {
                    dayState.slots.map { slot ->
                        OpeningHoursDto.DaySchedule(
                            dayOfWeek = dayOfWeek,
                            openTime = slot.openTime,
                            closeTime = slot.closeTime,
                            isClosed = false,
                        )
                    }
                }
            }

        val request = OpeningHoursDto.BulkUpdateRequestDto(days = days)
        val submittedSchedule = _uiState.value.weeklySchedule

        viewModelScope.launch {
            runCatchingApi { openingHoursApiClient.bulkUpdate(request) }
                .onSuccess { entries ->
                    logger.i { "OpeningHoursViewModel.saveSchedule: saved ${entries.size} entries" }
                    _uiState.update { it.copy(isSaving = false, savedSchedule = submittedSchedule) }
                }.onError { uiError ->
                    logger.e { "OpeningHoursViewModel.saveSchedule: failed" }
                    _uiState.update { it.copy(isSaving = false, error = uiError) }
                }
        }
    }

    private fun loadOverrides() {
        logger.i { "OpeningHoursViewModel.loadOverrides" }
        val today =
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date
        val startDate = today.minus(3, DateTimeUnit.MONTH)
        val endDate = today.plus(3, DateTimeUnit.MONTH)

        viewModelScope.launch {
            runCatchingApi {
                openingHoursApiClient.getOverridesByDateRange(startDate.toString(), endDate.toString())
            }.onSuccess { overrides ->
                _uiState.update { it.copy(overrides = overrides) }
            }.onError { uiError ->
                logger.e { "OpeningHoursViewModel.loadOverrides: failed" }
                _uiState.update { it.copy(error = uiError) }
            }
        }
    }

    private fun createOverride(request: OpeningHoursDto.CreateOverrideRequestDto) {
        logger.i { "OpeningHoursViewModel.createOverride: date=${request.overrideDate}" }
        if (!overrideHoursAreValid(request.isClosed, request.openTime, request.closeTime)) {
            logger.w { "OpeningHoursViewModel.createOverride: refused, the override opens at or after it closes" }
            return
        }
        viewModelScope.launch {
            runCatchingApi { openingHoursApiClient.createOverride(request) }
                .onSuccess {
                    _uiState.update { it.copy(showOverrideDialog = false, editingOverride = null) }
                    loadOverrides()
                }.onError { uiError ->
                    logger.e { "OpeningHoursViewModel.createOverride: failed" }
                    _uiState.update { it.copy(error = uiError) }
                }
        }
    }

    private fun updateOverride(
        id: Long,
        request: OpeningHoursDto.UpdateOverrideRequestDto,
    ) {
        logger.i { "OpeningHoursViewModel.updateOverride: id=$id" }
        if (!overrideHoursAreValid(request.isClosed ?: false, request.openTime, request.closeTime)) {
            logger.w { "OpeningHoursViewModel.updateOverride: refused, the override opens at or after it closes" }
            return
        }
        viewModelScope.launch {
            runCatchingApi { openingHoursApiClient.updateOverride(id, request) }
                .onSuccess {
                    _uiState.update { it.copy(showOverrideDialog = false, editingOverride = null) }
                    loadOverrides()
                }.onError { uiError ->
                    logger.e { "OpeningHoursViewModel.updateOverride: failed" }
                    _uiState.update { it.copy(error = uiError) }
                }
        }
    }

    private fun deleteOverride(id: Long) {
        logger.i { "OpeningHoursViewModel.deleteOverride: id=$id" }
        viewModelScope.launch {
            runCatchingApi { openingHoursApiClient.deleteOverride(id) }
                .onSuccess { loadOverrides() }
                .onError { uiError ->
                    logger.e { "OpeningHoursViewModel.deleteOverride: failed" }
                    _uiState.update { it.copy(error = uiError) }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
