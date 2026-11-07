package org.example.project.features.reservation.domain.service

import org.example.project.core.domain.dto.reservation.ReservationDto

interface TableAssignmentService {
    suspend fun autoAssign(
        reservationId: Long,
        employeeId: String,
    ): ReservationDto.AutoAssignResponseDto

    suspend fun manualAssign(
        reservationId: Long,
        employeeId: String,
        request: ReservationDto.ManualAssignRequestDto,
    ): List<ReservationDto.AssignmentDto>

    suspend fun unassignAll(reservationId: Long)

    suspend fun getAssignments(reservationId: Long): List<ReservationDto.AssignmentDto>

    /** Release all assignments for a reservation (called within existing transaction) */
    fun releaseAssignments(reservationId: Long)

    /** Bulk auto-assign tables to multiple reservations */
    suspend fun bulkAutoAssign(
        reservationIds: List<Long>,
        employeeId: String,
    ): ReservationDto.BulkAutoAssignResponseDto
}
