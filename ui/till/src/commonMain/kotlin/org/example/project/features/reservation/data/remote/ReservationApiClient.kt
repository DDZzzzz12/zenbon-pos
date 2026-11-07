package org.example.project.features.reservation.data.remote

import org.example.project.core.domain.dto.reservation.ReservationDto
import org.example.project.core.domain.dto.reservation.ReservationRuleDto
import org.example.project.core.domain.enums.reservation.ReservationStatus

interface ReservationApiClient {
    // Reservations
    suspend fun getReservationsByDate(
        date: String,
        status: ReservationStatus? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): List<ReservationDto.ResponseDto>

    suspend fun getReservationById(id: Long): ReservationDto.ResponseDto

    suspend fun createReservation(request: ReservationDto.CreateRequestDto): ReservationDto.ResponseDto

    suspend fun updateReservation(
        id: Long,
        request: ReservationDto.UpdateRequestDto,
    ): ReservationDto.ResponseDto

    suspend fun updateReservationStatus(
        id: Long,
        request: ReservationDto.UpdateStatusRequestDto,
    ): ReservationDto.ResponseDto

    suspend fun deleteReservation(id: Long)

    suspend fun searchReservations(
        query: String,
        limit: Int? = null,
        offset: Int? = null,
    ): List<ReservationDto.ResponseDto>

    suspend fun checkAvailability(
        date: String,
        time: String? = null,
        partySize: Int? = null,
    ): ReservationDto.AvailabilityResponseDto

    // Rules
    suspend fun getRulesByDateRange(
        startDate: String,
        endDate: String,
    ): List<ReservationRuleDto.ResponseDto>

    suspend fun getRuleById(id: Long): ReservationRuleDto.ResponseDto

    suspend fun createRule(request: ReservationRuleDto.CreateRequestDto): ReservationRuleDto.ResponseDto

    suspend fun updateRule(
        id: Long,
        request: ReservationRuleDto.UpdateRequestDto,
    ): ReservationRuleDto.ResponseDto

    suspend fun deleteRule(id: Long)

    // Table Assignments
    suspend fun autoAssign(reservationId: Long): ReservationDto.AutoAssignResponseDto

    suspend fun manualAssign(
        reservationId: Long,
        request: ReservationDto.ManualAssignRequestDto,
    ): List<ReservationDto.AssignmentDto>

    suspend fun unassignAll(reservationId: Long)

    suspend fun getAssignedTables(reservationId: Long): List<ReservationDto.AssignmentDto>

    suspend fun bulkAutoAssign(request: ReservationDto.BulkAutoAssignRequestDto): ReservationDto.BulkAutoAssignResponseDto
}
