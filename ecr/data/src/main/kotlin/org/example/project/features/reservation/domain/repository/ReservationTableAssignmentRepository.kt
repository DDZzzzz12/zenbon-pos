package org.example.project.features.reservation.domain.repository

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.example.project.features.reservation.domain.models.ReservationTableAssignment
import java.util.UUID

interface ReservationTableAssignmentRepository {
    fun getByReservationId(reservationId: Long): List<ReservationTableAssignment>

    fun getOccupiedTableIds(
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime,
        excludeReservationId: Long? = null,
        defaultDurationMinutes: Int = 120,
    ): Set<Long>

    fun create(
        reservationId: Long,
        tableId: Long,
        groupNumber: Int,
        assignedBy: UUID,
    ): ReservationTableAssignment

    fun deleteByReservationId(reservationId: Long): Int

    fun deleteByReservationAndTable(
        reservationId: Long,
        tableId: Long,
    ): Boolean
}
