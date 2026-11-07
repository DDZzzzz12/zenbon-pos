package org.example.project.features.reservation.domain.repository

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.example.project.core.domain.enums.reservation.ReservationStatus
import org.example.project.features.reservation.domain.models.Reservation
import java.util.UUID

interface ReservationRepository {
    fun getById(id: Long): Reservation?

    fun getByDate(
        date: LocalDate,
        status: ReservationStatus? = null,
        limit: Int? = null,
        offset: Int = 0,
    ): List<Reservation>

    fun getByDateRange(
        startDate: LocalDate,
        endDate: LocalDate,
        status: ReservationStatus? = null,
    ): List<Reservation>

    fun create(
        customerId: UUID?,
        guestName: String,
        guestPhone: String?,
        partySize: Int,
        reservationDate: LocalDate,
        reservationTime: LocalTime,
        durationMinutes: Int?,
        notes: String?,
        createdBy: UUID,
    ): Reservation

    fun update(reservation: Reservation): Reservation

    fun delete(id: Long)

    fun countByDateAndTimeRange(
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime,
        excludeStatuses: List<ReservationStatus> = listOf(ReservationStatus.CANCELLED, ReservationStatus.NO_SHOW),
    ): Int

    fun sumPartySizeByDateAndTimeRange(
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime,
        excludeStatuses: List<ReservationStatus> = listOf(ReservationStatus.CANCELLED, ReservationStatus.NO_SHOW),
    ): Int

    fun search(
        query: String,
        limit: Int? = null,
        offset: Int = 0,
    ): List<Reservation>
}
