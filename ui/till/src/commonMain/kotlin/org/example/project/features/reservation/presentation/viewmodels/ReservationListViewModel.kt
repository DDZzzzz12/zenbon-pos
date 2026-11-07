package org.example.project.features.reservation.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.example.project.core.domain.dto.openinghours.OpeningHoursDto
import org.example.project.core.domain.dto.reservation.ReservationDto
import org.example.project.core.domain.enums.reservation.ReservationStatus
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.ReservationKeys
import org.example.project.core.utils.LanguageManager
import org.example.project.features.openinghours.data.remote.OpeningHoursApiClient
import org.example.project.features.reservation.data.remote.ReservationApiClient
import org.example.project.features.reservation.presentation.state.DayStatus
import org.example.project.features.reservation.presentation.state.ReservationListEvent
import org.example.project.features.reservation.presentation.state.ReservationListState
import org.example.project.features.reservation.presentation.state.TimeSlotInfo
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class ReservationListViewModel(
    private val reservationApiClient: ReservationApiClient,
    private val openingHoursApiClient: OpeningHoursApiClient,
    private val languageManager: LanguageManager,
) : ViewModel() {
    private val logger = Logger.withTag("ReservationListViewModel")
    private var searchJob: Job? = null

    /** PosSuccessWatcher shows success text verbatim, so it is resolved here in the operator's language. */
    private fun localized(key: StringKey): String = getString(key, languageManager.currentLanguage.value)

    // Cached weekly schedule (loaded once)
    private var weeklySchedule: List<OpeningHoursDto.WeeklyResponseDto>? = null

    private val _uiState =
        MutableStateFlow(
            ReservationListState(
                selectedDate = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString(),
            ),
        )
    val uiState: StateFlow<ReservationListState> = _uiState.asStateFlow()

    init {
        loadWeeklySchedule()
        loadReservationsAndHours()
    }

    fun onEvent(event: ReservationListEvent) {
        when (event) {
            is ReservationListEvent.DateChanged -> {
                _uiState.update { it.copy(selectedDate = event.date) }
                loadReservationsAndHours()
            }
            is ReservationListEvent.UpdateStatus -> updateStatus(event.id, event.status)
            is ReservationListEvent.DeleteReservation -> deleteReservation(event.id)
            is ReservationListEvent.Refresh -> loadReservationsAndHours()
            is ReservationListEvent.ShowCreateDialog -> _uiState.update { it.copy(showCreateDialog = true) }
            is ReservationListEvent.HideCreateDialog -> _uiState.update { it.copy(showCreateDialog = false) }
            is ReservationListEvent.SearchQueryChanged -> {
                _uiState.update { it.copy(searchQuery = event.query) }
                if (event.query.isBlank()) {
                    searchJob?.cancel()
                    _uiState.update { it.copy(isSearching = false, searchResults = emptyList()) }
                } else {
                    searchReservations(event.query)
                }
            }
            is ReservationListEvent.ClearSearch -> {
                searchJob?.cancel()
                _uiState.update { it.copy(searchQuery = "", isSearching = false, searchResults = emptyList()) }
            }
            is ReservationListEvent.OpenAssignmentSheet -> {
                _uiState.update { it.copy(reservationForAssignment = event.reservation) }
            }
            is ReservationListEvent.CloseAssignmentSheet -> {
                _uiState.update { it.copy(reservationForAssignment = null) }
            }
            is ReservationListEvent.QuickAutoAssign -> quickAutoAssign(event.reservationId)
            is ReservationListEvent.BulkAutoAssign -> bulkAutoAssign(event.slotIndex)
        }
    }

    private fun loadWeeklySchedule() {
        viewModelScope.launch {
            logger.i { "ReservationListViewModel.loadWeeklySchedule" }
            runCatchingApi { openingHoursApiClient.getAll() }
                .onSuccess { schedule ->
                    weeklySchedule = schedule
                    logger.i { "ReservationListViewModel.loadWeeklySchedule: loaded ${schedule.size} entries" }
                }.onFailure {
                    logger.e { "ReservationListViewModel.loadWeeklySchedule: failed, treating as always open" }
                    weeklySchedule = emptyList()
                }
        }
    }

    private fun loadReservationsAndHours() {
        viewModelScope.launch {
            val date = _uiState.value.selectedDate
            logger.i { "ReservationListViewModel.loadReservationsAndHours: date=$date" }
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Load reservations
            val reservationsResult = runCatchingApi { reservationApiClient.getReservationsByDate(date) }
            if (reservationsResult.isFailure) {
                logger.e { "ReservationListViewModel.loadReservationsAndHours: failed to load reservations" }
                reservationsResult.onError { uiError ->
                    _uiState.update { it.copy(isLoading = false, error = uiError) }
                }
                return@launch
            }
            val reservations = reservationsResult.getOrThrow()

            // Check for date override
            val override = loadOverrideForDate(date)

            // Resolve day status
            val dayStatus = resolveDayStatus(date, override, reservations)
            val overrideReason = override?.reason

            _uiState.update {
                it.copy(
                    reservations = reservations,
                    dayStatus = dayStatus,
                    overrideReason = overrideReason,
                    isLoading = false,
                )
            }
        }
    }

    private suspend fun loadOverrideForDate(date: String): OpeningHoursDto.OverrideResponseDto? =
        runCatchingApi { openingHoursApiClient.getOverridesByDateRange(date, date) }
            .getOrNull()
            ?.firstOrNull()

    private fun resolveDayStatus(
        dateStr: String,
        override: OpeningHoursDto.OverrideResponseDto?,
        reservations: List<ReservationDto.ResponseDto>,
    ): DayStatus {
        // 1. Check override first
        if (override != null) {
            if (override.isClosed) {
                return DayStatus.Closed(reason = override.reason)
            }
            // Override with custom times — single slot
            if (override.openTime != null && override.closeTime != null) {
                return DayStatus.Open(
                    slots =
                        listOf(
                            TimeSlotInfo(
                                openTime = override.openTime!!,
                                closeTime = override.closeTime!!,
                                reservations =
                                    filterReservationsForSlot(
                                        reservations,
                                        override.openTime!!,
                                        override.closeTime!!,
                                    ),
                            ),
                        ),
                )
            }
        }

        // 2. Fall back to weekly schedule
        val schedule = weeklySchedule
        if (schedule.isNullOrEmpty()) {
            return DayStatus.AlwaysOpen(reservations = reservations)
        }

        val localDate =
            try {
                LocalDate.parse(dateStr)
            } catch (_: Exception) {
                return DayStatus.AlwaysOpen(reservations)
            }
        val dayOfWeek = localDate.dayOfWeek.ordinal // Monday=0 .. Sunday=6

        val daySlots = schedule.filter { it.dayOfWeek == dayOfWeek }
        if (daySlots.isEmpty()) {
            return DayStatus.AlwaysOpen(reservations = reservations)
        }

        if (daySlots.all { it.isClosed }) {
            return DayStatus.Closed()
        }

        val openSlots = daySlots.filter { !it.isClosed }.sortedBy { it.openTime }
        val slotInfos =
            openSlots.map { slot ->
                TimeSlotInfo(
                    openTime = slot.openTime,
                    closeTime = slot.closeTime,
                    reservations = filterReservationsForSlot(reservations, slot.openTime, slot.closeTime),
                )
            }

        return DayStatus.Open(slots = slotInfos)
    }

    private fun filterReservationsForSlot(
        reservations: List<ReservationDto.ResponseDto>,
        openTime: String,
        closeTime: String,
    ): List<ReservationDto.ResponseDto> =
        reservations.filter { res ->
            val resTime = res.reservationTime.take(5) // "HH:mm"
            resTime >= openTime.take(5) && resTime < closeTime.take(5)
        }

    private fun updateStatus(
        id: Long,
        status: ReservationStatus,
    ) {
        viewModelScope.launch {
            logger.i { "ReservationListViewModel.updateStatus: id=$id, status=$status" }
            val request = ReservationDto.UpdateStatusRequestDto(status = status)
            runCatchingApi { reservationApiClient.updateReservationStatus(id, request) }
                .onSuccess {
                    _uiState.update { it.copy(successMessage = localized(ReservationKeys.reservationStatusUpdated)) }
                    loadReservationsAndHours()
                }.onError { uiError ->
                    _uiState.update { it.copy(error = uiError) }
                }
        }
    }

    private fun deleteReservation(id: Long) {
        viewModelScope.launch {
            logger.i { "ReservationListViewModel.deleteReservation: id=$id" }
            runCatchingApi { reservationApiClient.deleteReservation(id) }
                .onSuccess {
                    _uiState.update { it.copy(successMessage = localized(ReservationKeys.reservationDeleted)) }
                    loadReservationsAndHours()
                }.onError { uiError ->
                    _uiState.update { it.copy(error = uiError) }
                }
        }
    }

    private fun searchReservations(query: String) {
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                delay(300) // debounce
                logger.i { "ReservationListViewModel.searchReservations: query='$query'" }
                _uiState.update { it.copy(isSearching = true) }

                runCatchingApi { reservationApiClient.searchReservations(query) }
                    .onSuccess { results ->
                        logger.i { "ReservationListViewModel.searchReservations: found ${results.size} results" }
                        _uiState.update { it.copy(searchResults = results, isSearching = false) }
                    }.onError { uiError ->
                        logger.e { "ReservationListViewModel.searchReservations: failed" }
                        _uiState.update { it.copy(isSearching = false, error = uiError) }
                    }
            }
    }

    private fun quickAutoAssign(reservationId: Long) {
        viewModelScope.launch {
            logger.i { "ReservationListViewModel.quickAutoAssign: reservationId=$reservationId" }
            runCatchingApi { reservationApiClient.autoAssign(reservationId) }
                .onSuccess { data ->
                    val tableNames = data.new.joinToString(", ") { it.tableNumber }
                    _uiState.update { it.copy(successMessage = tableNames) }
                    loadReservationsAndHours()
                }.onError { uiError ->
                    _uiState.update { it.copy(error = uiError) }
                }
        }
    }

    private fun bulkAutoAssign(slotIndex: Int) {
        val dayStatus = _uiState.value.dayStatus
        val slotReservations =
            when (dayStatus) {
                is DayStatus.Open -> dayStatus.slots.getOrNull(slotIndex)?.reservations ?: return
                is DayStatus.AlwaysOpen -> dayStatus.reservations
                is DayStatus.Closed -> return
            }

        // Only auto-assign CONFIRMED reservations without existing assignments
        val unassigned =
            slotReservations.filter {
                it.status == ReservationStatus.CONFIRMED && it.tableAssignments.isEmpty()
            }

        if (unassigned.isEmpty()) return

        logger.i { "ReservationListViewModel.bulkAutoAssign: slotIndex=$slotIndex, count=${unassigned.size}" }
        _uiState.update { it.copy(isBulkAssigning = true) }

        viewModelScope.launch {
            val request =
                ReservationDto.BulkAutoAssignRequestDto(
                    reservationIds = unassigned.map { it.id },
                )
            runCatchingApi { reservationApiClient.bulkAutoAssign(request) }
                .onSuccess { data ->
                    logger.i { "ReservationListViewModel.bulkAutoAssign: success=${data.successCount}, failed=${data.failCount}" }
                    val message =
                        if (data.failCount == 0) {
                            "${data.successCount} assigned"
                        } else {
                            "${data.successCount} assigned, ${data.failCount} failed"
                        }
                    _uiState.update { it.copy(successMessage = message, isBulkAssigning = false) }
                    loadReservationsAndHours()
                }.onError { uiError ->
                    logger.e { "ReservationListViewModel.bulkAutoAssign: failed" }
                    _uiState.update { it.copy(error = uiError, isBulkAssigning = false) }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearSuccessMessage() {
        _uiState.update { it.copy(successMessage = null) }
    }
}
