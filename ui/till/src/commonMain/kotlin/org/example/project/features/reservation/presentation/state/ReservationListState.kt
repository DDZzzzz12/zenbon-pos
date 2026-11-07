package org.example.project.features.reservation.presentation.state

import org.example.project.core.domain.dto.reservation.ReservationDto
import org.example.project.core.domain.enums.reservation.ReservationStatus
import org.example.project.core.presentation.error.UiError

/**
 * Represents a time slot from opening hours (e.g., "Lunch 10:00-14:00")
 */
data class TimeSlotInfo(
    val openTime: String,
    val closeTime: String,
    val reservations: List<ReservationDto.ResponseDto> = emptyList(),
)

/**
 * The day's opening status resolved from opening hours + overrides
 */
sealed interface DayStatus {
    /** Restaurant is closed — show banner with optional reason */
    data class Closed(
        val reason: String? = null,
    ) : DayStatus

    /** Restaurant is open with specific time slots */
    data class Open(
        val slots: List<TimeSlotInfo>,
    ) : DayStatus

    /** No opening hours configured — treat as always open */
    data class AlwaysOpen(
        val reservations: List<ReservationDto.ResponseDto> = emptyList(),
    ) : DayStatus
}

data class ReservationListState(
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val successMessage: String? = null,
    val reservations: List<ReservationDto.ResponseDto> = emptyList(),
    val selectedDate: String = "",
    val showCreateDialog: Boolean = false,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<ReservationDto.ResponseDto> = emptyList(),
    val reservationForAssignment: ReservationDto.ResponseDto? = null,
    val dayStatus: DayStatus = DayStatus.AlwaysOpen(),
    val overrideReason: String? = null,
    val isBulkAssigning: Boolean = false,
)

sealed interface ReservationListEvent {
    data class DateChanged(
        val date: String,
    ) : ReservationListEvent

    data class UpdateStatus(
        val id: Long,
        val status: ReservationStatus,
    ) : ReservationListEvent

    data class DeleteReservation(
        val id: Long,
    ) : ReservationListEvent

    data object Refresh : ReservationListEvent

    data object ShowCreateDialog : ReservationListEvent

    data object HideCreateDialog : ReservationListEvent

    data class SearchQueryChanged(
        val query: String,
    ) : ReservationListEvent

    data object ClearSearch : ReservationListEvent

    data class OpenAssignmentSheet(
        val reservation: ReservationDto.ResponseDto,
    ) : ReservationListEvent

    data class QuickAutoAssign(
        val reservationId: Long,
    ) : ReservationListEvent

    data object CloseAssignmentSheet : ReservationListEvent

    /** Bulk auto-assign all unassigned CONFIRMED reservations in a specific time slot */
    data class BulkAutoAssign(
        val slotIndex: Int,
    ) : ReservationListEvent
}
