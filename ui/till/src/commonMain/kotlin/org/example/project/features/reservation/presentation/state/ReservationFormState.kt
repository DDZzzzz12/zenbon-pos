package org.example.project.features.reservation.presentation.state

import org.example.project.core.domain.dto.reservation.ReservationDto
import org.example.project.core.domain.dto.reservation.ReservationRuleDto
import org.example.project.core.presentation.error.UiError

data class ReservationFormState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: UiError? = null,
    val successMessage: String? = null,
    val guestName: String = "",
    val guestPhone: String = "",
    val partySize: Int = 2,
    val reservationDate: String = "",
    val reservationTime: String = "",
    val durationMinutes: Int? = null,
    val notes: String = "",
    val customerId: String? = null,
    val customerName: String? = null,
    val editingReservationId: Long? = null,
    val availability: ReservationDto.AvailabilityResponseDto? = null,
    val activeRule: ReservationRuleDto.ResponseDto? = null,
) {
    val isEditing: Boolean get() = editingReservationId != null
}

sealed interface ReservationFormEvent {
    data class GuestNameChanged(
        val name: String,
    ) : ReservationFormEvent

    data class GuestPhoneChanged(
        val phone: String,
    ) : ReservationFormEvent

    data class PartySizeChanged(
        val size: Int,
    ) : ReservationFormEvent

    data class DateChanged(
        val date: String,
    ) : ReservationFormEvent

    data class TimeChanged(
        val time: String,
    ) : ReservationFormEvent

    data class NotesChanged(
        val notes: String,
    ) : ReservationFormEvent

    data class CustomerSelected(
        val id: String?,
        val name: String?,
    ) : ReservationFormEvent

    data class LoadForEdit(
        val reservation: ReservationDto.ResponseDto,
    ) : ReservationFormEvent

    data object Save : ReservationFormEvent

    data object ClearForm : ReservationFormEvent
}
