package org.example.project.features.reservation.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.core.domain.dto.reservation.ReservationDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.features.reservation.data.remote.ReservationApiClient
import org.example.project.features.reservation.presentation.state.TableAssignmentEvent
import org.example.project.features.reservation.presentation.state.TableAssignmentState

class TableAssignmentViewModel(
    private val reservationApiClient: ReservationApiClient,
) : ViewModel() {
    private val logger = Logger.withTag("TableAssignmentViewModel")

    private val _uiState = MutableStateFlow(TableAssignmentState())
    val uiState: StateFlow<TableAssignmentState> = _uiState.asStateFlow()

    var onAssignmentChanged: (() -> Unit)? = null

    fun onEvent(event: TableAssignmentEvent) {
        when (event) {
            is TableAssignmentEvent.Open -> open(event.reservation)
            is TableAssignmentEvent.Close -> _uiState.update { TableAssignmentState() }
            is TableAssignmentEvent.AutoAssign -> autoAssign()
            is TableAssignmentEvent.ManualAssign -> manualAssign()
            is TableAssignmentEvent.RequestUnassignAll -> _uiState.update { it.copy(showUnassignConfirmDialog = true) }
            is TableAssignmentEvent.ConfirmUnassignAll -> confirmUnassignAll()
            is TableAssignmentEvent.DismissUnassignConfirm -> _uiState.update { it.copy(showUnassignConfirmDialog = false) }
            is TableAssignmentEvent.SelectGroup ->
                _uiState.update {
                    it.copy(
                        selectedGroup = event.groupNumber,
                        selectedTableIds = emptySet(),
                    )
                }
            is TableAssignmentEvent.AddGroup -> addGroup()
            is TableAssignmentEvent.ToggleTable -> toggleTable(event.tableId)
            is TableAssignmentEvent.ToggleManualSection -> _uiState.update { it.copy(isManualExpanded = !it.isManualExpanded) }
        }
    }

    private fun open(reservation: ReservationDto.ResponseDto) {
        logger.i { "TableAssignmentViewModel.open: reservationId=${reservation.id}" }
        _uiState.update { it.copy(reservation = reservation, isLoading = true, error = null) }

        viewModelScope.launch {
            val assignmentsResult = runCatchingApi { reservationApiClient.getAssignedTables(reservation.id) }
            if (assignmentsResult.isFailure) {
                logger.e { "TableAssignmentViewModel.open: failed to load assignments" }
                assignmentsResult.onError { uiError ->
                    _uiState.update { it.copy(isLoading = false, error = uiError) }
                }
                return@launch
            }
            val freshAssignments = assignmentsResult.getOrThrow()
            _uiState.update { it.copy(assignments = freshAssignments) }

            runCatchingApi {
                reservationApiClient.checkAvailability(
                    date = reservation.reservationDate,
                    time = reservation.reservationTime,
                    partySize = reservation.partySize,
                )
            }.onSuccess { availability ->
                val assignedTableIds = freshAssignments.map { it.tableId }.toSet()
                val filtered = availability.availableTableDetails.filter { it.tableId !in assignedTableIds }
                _uiState.update { it.copy(availableTables = filtered, isLoading = false) }
            }.onError { uiError ->
                logger.e { "TableAssignmentViewModel.open: failed to load availability" }
                _uiState.update { it.copy(isLoading = false, error = uiError) }
            }
        }
    }

    private fun autoAssign() {
        val reservationId = _uiState.value.reservation?.id ?: return
        logger.i { "TableAssignmentViewModel.autoAssign: reservationId=$reservationId" }
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            runCatchingApi { reservationApiClient.autoAssign(reservationId) }
                .onSuccess { data ->
                    logger.i { "TableAssignmentViewModel.autoAssign: assigned ${data.new.size} new tables" }
                    _uiState.update {
                        it.copy(
                            assignments = data.kept + data.new,
                            isLoading = false,
                        )
                    }
                    onAssignmentChanged?.invoke()
                    refreshAvailableTables()
                }.onError { uiError ->
                    logger.e { "TableAssignmentViewModel.autoAssign: failed" }
                    _uiState.update { it.copy(isLoading = false, error = uiError) }
                }
        }
    }

    private fun manualAssign() {
        val reservationId = _uiState.value.reservation?.id ?: return
        val selectedIds = _uiState.value.selectedTableIds
        val group = _uiState.value.selectedGroup
        if (selectedIds.isEmpty()) return

        logger.i { "TableAssignmentViewModel.manualAssign: reservationId=$reservationId, tables=$selectedIds, group=$group" }
        _uiState.update { it.copy(isLoading = true, error = null) }

        val request =
            ReservationDto.ManualAssignRequestDto(
                assignments = selectedIds.map { ReservationDto.ManualAssignRequestDto.TableAssignment(it, group) },
            )

        viewModelScope.launch {
            runCatchingApi { reservationApiClient.manualAssign(reservationId, request) }
                .onSuccess { newAssignments ->
                    logger.i { "TableAssignmentViewModel.manualAssign: assigned ${newAssignments.size} tables" }
                    _uiState.update {
                        it.copy(
                            assignments = it.assignments + newAssignments,
                            selectedTableIds = emptySet(),
                            isLoading = false,
                        )
                    }
                    onAssignmentChanged?.invoke()
                    refreshAvailableTables()
                }.onError { uiError ->
                    logger.e { "TableAssignmentViewModel.manualAssign: failed" }
                    _uiState.update { it.copy(isLoading = false, error = uiError) }
                }
        }
    }

    private fun confirmUnassignAll() {
        val reservationId = _uiState.value.reservation?.id ?: return
        logger.i { "TableAssignmentViewModel.confirmUnassignAll: reservationId=$reservationId" }
        _uiState.update { it.copy(showUnassignConfirmDialog = false, isLoading = true, error = null) }

        viewModelScope.launch {
            runCatchingApi { reservationApiClient.unassignAll(reservationId) }
                .onSuccess {
                    _uiState.update { it.copy(assignments = emptyList(), isLoading = false) }
                    onAssignmentChanged?.invoke()
                    refreshAvailableTables()
                }.onError { uiError ->
                    logger.e { "TableAssignmentViewModel.confirmUnassignAll: failed" }
                    _uiState.update { it.copy(isLoading = false, error = uiError) }
                }
        }
    }

    private fun addGroup() {
        val reservation = _uiState.value.reservation ?: return
        val newGroups = reservation.tableGroups + 1
        if (newGroups > reservation.partySize) return

        logger.i { "TableAssignmentViewModel.addGroup: reservationId=${reservation.id}, newGroups=$newGroups" }
        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            val request = ReservationDto.UpdateRequestDto(tableGroups = newGroups)
            runCatchingApi { reservationApiClient.updateReservation(reservation.id, request) }
                .onSuccess { updated ->
                    _uiState.update { it.copy(reservation = updated, isLoading = false) }
                    onAssignmentChanged?.invoke()
                }.onError { uiError ->
                    logger.e { "TableAssignmentViewModel.addGroup: failed" }
                    _uiState.update { it.copy(isLoading = false, error = uiError) }
                }
        }
    }

    private fun toggleTable(tableId: String) {
        _uiState.update {
            val newSet =
                if (tableId in it.selectedTableIds) {
                    it.selectedTableIds - tableId
                } else {
                    it.selectedTableIds + tableId
                }
            it.copy(selectedTableIds = newSet)
        }
    }

    private suspend fun refreshAvailableTables() {
        val reservation = _uiState.value.reservation ?: return
        runCatchingApi {
            reservationApiClient.checkAvailability(
                date = reservation.reservationDate,
                time = reservation.reservationTime,
                partySize = reservation.partySize,
            )
        }.onSuccess { availability ->
            val assignedTableIds =
                _uiState.value.assignments
                    .map { it.tableId }
                    .toSet()
            val filtered = availability.availableTableDetails.filter { it.tableId !in assignedTableIds }
            _uiState.update { it.copy(availableTables = filtered) }
        }
        // silently fail — available tables is supplementary
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
