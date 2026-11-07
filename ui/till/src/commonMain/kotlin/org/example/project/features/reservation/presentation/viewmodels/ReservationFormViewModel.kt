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
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.ReservationKeys
import org.example.project.core.utils.LanguageManager
import org.example.project.features.reservation.data.remote.ReservationApiClient
import org.example.project.features.reservation.presentation.state.ReservationFormEvent
import org.example.project.features.reservation.presentation.state.ReservationFormState

class ReservationFormViewModel(
    private val reservationApiClient: ReservationApiClient,
    private val languageManager: LanguageManager,
) : ViewModel() {
    private val logger = Logger.withTag("ReservationFormViewModel")

    /** PosSuccessWatcher shows success text verbatim, so it is resolved here in the operator's language. */
    private fun localized(key: StringKey): String = getString(key, languageManager.currentLanguage.value)

    private val _uiState = MutableStateFlow(ReservationFormState())
    val uiState: StateFlow<ReservationFormState> = _uiState.asStateFlow()

    fun onEvent(event: ReservationFormEvent) {
        when (event) {
            is ReservationFormEvent.GuestNameChanged -> {
                _uiState.update { it.copy(guestName = event.name) }
            }
            is ReservationFormEvent.GuestPhoneChanged -> {
                _uiState.update { it.copy(guestPhone = event.phone) }
            }
            is ReservationFormEvent.PartySizeChanged -> _uiState.update { it.copy(partySize = event.size) }
            is ReservationFormEvent.DateChanged -> {
                _uiState.update { it.copy(reservationDate = event.date) }
                checkAvailabilityAndRule(event.date)
            }
            is ReservationFormEvent.TimeChanged -> _uiState.update { it.copy(reservationTime = event.time) }
            is ReservationFormEvent.NotesChanged -> _uiState.update { it.copy(notes = event.notes) }
            is ReservationFormEvent.CustomerSelected ->
                _uiState.update {
                    it.copy(customerId = event.id, customerName = event.name)
                }
            is ReservationFormEvent.LoadForEdit -> loadForEdit(event.reservation)
            is ReservationFormEvent.Save -> save()
            is ReservationFormEvent.ClearForm -> _uiState.update { ReservationFormState() }
        }
    }

    private fun loadForEdit(reservation: ReservationDto.ResponseDto) {
        _uiState.update {
            it.copy(
                editingReservationId = reservation.id,
                guestName = reservation.guestName,
                guestPhone = reservation.guestPhone ?: "",
                partySize = reservation.partySize,
                reservationDate = reservation.reservationDate,
                reservationTime = reservation.reservationTime,
                durationMinutes = reservation.durationMinutes,
                notes = reservation.notes ?: "",
                customerId = reservation.customerId,
                customerName = reservation.customerName,
            )
        }
        checkAvailabilityAndRule(reservation.reservationDate)
    }

    private fun checkAvailabilityAndRule(date: String) {
        if (date.isBlank()) return
        viewModelScope.launch {
            logger.i { "ReservationFormViewModel.checkAvailabilityAndRule: date=$date" }
            runCatchingApi { reservationApiClient.checkAvailability(date) }
                .onSuccess { availability ->
                    _uiState.update { it.copy(availability = availability) }
                }.onFailure { e ->
                    logger.e { "ReservationFormViewModel.checkAvailabilityAndRule: failed: $e" }
                }

            runCatchingApi { reservationApiClient.getRulesByDateRange(date, date) }
                .onSuccess { rules ->
                    _uiState.update { it.copy(activeRule = rules.firstOrNull()) }
                }
            // non-critical: ignore failure
        }
    }

    private fun save() {
        val state = _uiState.value
        if (state.guestName.isBlank() || state.reservationDate.isBlank() || state.reservationTime.isBlank()) {
            _uiState.update {
                it.copy(
                    error =
                        UiError.FromServer(
                            code = PosErrorCode.VALIDATION_FAILED,
                            traceId = null,
                            titleKey = CommonKeys.error,
                            messageKey = CommonKeys.unknownErrorMessage,
                            httpStatus = 400,
                            rawDetail = "guestName, reservationDate, reservationTime",
                        ),
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }

            if (state.isEditing) {
                logger.i { "ReservationFormViewModel.save: updating reservation id=${state.editingReservationId}" }
                val request =
                    ReservationDto.UpdateRequestDto(
                        customerId = state.customerId,
                        guestName = state.guestName,
                        guestPhone = state.guestPhone.ifBlank { null },
                        partySize = state.partySize,
                        reservationDate = state.reservationDate,
                        reservationTime = state.reservationTime,
                        notes = state.notes.ifBlank { null },
                    )
                runCatchingApi {
                    reservationApiClient.updateReservation(state.editingReservationId!!, request)
                }.onSuccess {
                    _uiState.update { it.copy(isSaving = false, successMessage = localized(ReservationKeys.reservationUpdated)) }
                }.onError { uiError ->
                    _uiState.update { it.copy(isSaving = false, error = uiError) }
                }
            } else {
                logger.i { "ReservationFormViewModel.save: creating reservation for ${state.guestName}" }
                val request =
                    ReservationDto.CreateRequestDto(
                        customerId = state.customerId,
                        guestName = state.guestName,
                        guestPhone = state.guestPhone.ifBlank { null },
                        partySize = state.partySize,
                        reservationDate = state.reservationDate,
                        reservationTime = state.reservationTime,
                        durationMinutes = state.durationMinutes,
                        notes = state.notes.ifBlank { null },
                    )
                runCatchingApi { reservationApiClient.createReservation(request) }
                    .onSuccess {
                        _uiState.update { it.copy(isSaving = false, successMessage = localized(ReservationKeys.reservationCreated)) }
                    }.onError { uiError ->
                        _uiState.update { it.copy(isSaving = false, error = uiError) }
                    }
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
