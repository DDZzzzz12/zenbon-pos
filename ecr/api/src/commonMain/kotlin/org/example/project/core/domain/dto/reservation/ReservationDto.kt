package org.example.project.core.domain.dto.reservation

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.reservation.ReservationStatus

object ReservationDto {
    @Serializable
    data class ResponseDto(
        val id: Long,
        val customerId: String? = null,
        val customerName: String? = null,
        val guestName: String,
        val guestPhone: String? = null,
        val partySize: Int,
        val reservationDate: String,
        val reservationTime: String,
        val durationMinutes: Int? = null,
        val status: ReservationStatus,
        val notes: String? = null,
        val createdBy: String,
        val createdByName: String? = null,
        val updatedBy: String? = null,
        val createdAt: String,
        val updatedAt: String,
        val tableGroups: Int = 1,
        val maxTablesPerGroup: Int? = null,
        val locationPreference: String? = null,
        val tableAssignments: List<AssignmentDto> = emptyList(),
    ) {
        companion object
    }

    @Serializable
    data class CreateRequestDto(
        val customerId: String? = null,
        val guestName: String,
        val guestPhone: String? = null,
        val partySize: Int,
        val reservationDate: String,
        val reservationTime: String,
        val durationMinutes: Int? = null,
        val notes: String? = null,
        val tableGroups: Int? = null,
        val maxTablesPerGroup: Int? = null,
        val locationPreference: String? = null,
    ) {
        fun validate() {
            require(guestName.isNotBlank()) { "Guest name is required" }
            require(guestName.length <= 100) { "Guest name must not exceed 100 characters" }
            require(partySize >= 1) { "Party size must be at least 1" }
            guestPhone?.let {
                require(it.length <= 20) { "Phone number must not exceed 20 characters" }
            }
            notes?.let {
                require(it.length <= 500) { "Notes must not exceed 500 characters" }
            }
            tableGroups?.let { require(it >= 1) { "Table groups must be at least 1" } }
            maxTablesPerGroup?.let { require(it >= 1) { "Max tables per group must be at least 1" } }
        }
    }

    @Serializable
    data class UpdateRequestDto(
        val customerId: String? = null,
        val guestName: String? = null,
        val guestPhone: String? = null,
        val partySize: Int? = null,
        val reservationDate: String? = null,
        val reservationTime: String? = null,
        val durationMinutes: Int? = null,
        val notes: String? = null,
        val tableGroups: Int? = null,
        val maxTablesPerGroup: Int? = null,
        val locationPreference: String? = null,
    ) {
        fun validate() {
            guestName?.let {
                require(it.isNotBlank()) { "Guest name cannot be blank" }
                require(it.length <= 100) { "Guest name must not exceed 100 characters" }
            }
            partySize?.let {
                require(it >= 1) { "Party size must be at least 1" }
            }
            guestPhone?.let {
                require(it.length <= 20) { "Phone number must not exceed 20 characters" }
            }
            notes?.let {
                require(it.length <= 500) { "Notes must not exceed 500 characters" }
            }
        }
    }

    @Serializable
    data class UpdateStatusRequestDto(
        val status: ReservationStatus,
    )

    @Serializable
    data class AvailabilityResponseDto(
        val date: String,
        val time: String? = null,
        val isOpen: Boolean,
        val openTime: String? = null,
        val closeTime: String? = null,
        val totalTables: Int,
        val availableTables: Int,
        val canAccommodate: Boolean? = null,
        val availableTableDetails: List<AvailableTableDto> = emptyList(),
        val hasRule: Boolean,
        val ruleType: String? = null,
        val availableSlots: List<String>? = null,
        val warningMessage: String? = null,
    )

    @Serializable
    data class AvailableTableDto(
        val tableId: String,
        val tableNumber: String,
        val seats: Int,
        val locationType: String,
        val combinableWith: List<String> = emptyList(),
    )

    @Serializable
    data class ManualAssignRequestDto(
        val assignments: List<TableAssignment>,
    ) {
        @Serializable
        data class TableAssignment(
            val tableId: String,
            val groupNumber: Int,
        )

        fun validate() {
            require(assignments.isNotEmpty()) { "At least one table assignment is required" }
            assignments.forEach {
                require(it.groupNumber >= 1) { "Group number must be >= 1" }
            }
        }
    }

    @Serializable
    data class AutoAssignResponseDto(
        val kept: List<AssignmentDto>,
        val new: List<AssignmentDto>,
        val totalSeatsAssigned: Int,
        val partySize: Int,
    )

    @Serializable
    data class AssignmentDto(
        val tableId: String,
        val tableNumber: String,
        val seats: Int,
        val groupNumber: Int,
        val assignedBy: String? = null,
        val assignedAt: String? = null,
    )

    @Serializable
    data class BulkAutoAssignRequestDto(
        val reservationIds: List<Long>,
    ) {
        fun validate() {
            require(reservationIds.isNotEmpty()) { "At least one reservation ID is required" }
        }
    }

    @Serializable
    data class BulkAutoAssignResponseDto(
        val results: List<BulkAssignResult>,
        val successCount: Int,
        val failCount: Int,
    )

    @Serializable
    data class BulkAssignResult(
        val reservationId: Long,
        val success: Boolean,
        val assignedTables: List<String> = emptyList(),
        val errorMessage: String? = null,
    )
}
