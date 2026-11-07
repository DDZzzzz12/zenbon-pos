package org.example.project.features.reservation.data.repository

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.example.project.core.domain.enums.reservation.ReservationStatus
import org.example.project.features.customer.domain.models.Customer
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.reservation.database.tables.ReservationTable
import org.example.project.features.reservation.domain.models.Reservation
import org.example.project.features.reservation.domain.repository.ReservationRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import java.util.UUID

class ReservationRepositoryImpl : ReservationRepository {
    override fun getById(id: Long): Reservation? = Reservation.findById(id)

    override fun getByDate(
        date: LocalDate,
        status: ReservationStatus?,
        limit: Int?,
        offset: Int,
    ): List<Reservation> {
        var query =
            if (status != null) {
                Reservation.find {
                    (ReservationTable.reservationDate eq date) and (ReservationTable.status eq status)
                }
            } else {
                Reservation.find { ReservationTable.reservationDate eq date }
            }

        query = query.orderBy(ReservationTable.reservationTime to SortOrder.ASC)

        if (offset > 0) {
            query = query.limit(count = limit ?: Int.MAX_VALUE).offset(offset.toLong())
        } else if (limit != null) {
            query = query.limit(count = limit)
        }

        return query.toList()
    }

    override fun getByDateRange(
        startDate: LocalDate,
        endDate: LocalDate,
        status: ReservationStatus?,
    ): List<Reservation> {
        val baseCondition =
            (ReservationTable.reservationDate greaterEq startDate) and
                (ReservationTable.reservationDate lessEq endDate)

        val condition =
            if (status != null) {
                baseCondition and (ReservationTable.status eq status)
            } else {
                baseCondition
            }

        return Reservation
            .find { condition }
            .orderBy(
                ReservationTable.reservationDate to SortOrder.ASC,
                ReservationTable.reservationTime to SortOrder.ASC,
            ).toList()
    }

    override fun create(
        customerId: UUID?,
        guestName: String,
        guestPhone: String?,
        partySize: Int,
        reservationDate: LocalDate,
        reservationTime: LocalTime,
        durationMinutes: Int?,
        notes: String?,
        createdBy: UUID,
    ): Reservation {
        val now = currentTimeStamp()
        return Reservation.new {
            this.customer = customerId?.let { Customer.findById(it) }
            this.guestName = guestName
            this.guestPhone = guestPhone
            this.partySize = partySize
            this.reservationDate = reservationDate
            this.reservationTime = reservationTime
            this.durationMinutes = durationMinutes
            this.notes = notes
            this.createdByEmployee = Employee.findById(createdBy)!!
            this.status = ReservationStatus.CONFIRMED
            this.createdAt = now
            this.updatedAt = now
        }
    }

    override fun update(reservation: Reservation): Reservation {
        reservation.updatedAt = currentTimeStamp()
        return reservation
    }

    override fun delete(id: Long) {
        Reservation[id].delete()
    }

    override fun countByDateAndTimeRange(
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime,
        excludeStatuses: List<ReservationStatus>,
    ): Int =
        Reservation
            .find {
                (ReservationTable.reservationDate eq date) and
                    (ReservationTable.reservationTime greaterEq startTime) and
                    (ReservationTable.reservationTime lessEq endTime) and
                    (ReservationTable.status notInList excludeStatuses)
            }.count()
            .toInt()

    override fun sumPartySizeByDateAndTimeRange(
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime,
        excludeStatuses: List<ReservationStatus>,
    ): Int =
        Reservation
            .find {
                (ReservationTable.reservationDate eq date) and
                    (ReservationTable.reservationTime greaterEq startTime) and
                    (ReservationTable.reservationTime lessEq endTime) and
                    (ReservationTable.status notInList excludeStatuses)
            }.sumOf { it.partySize }

    override fun search(
        query: String,
        limit: Int?,
        offset: Int,
    ): List<Reservation> {
        val searchTerm = "%${query.lowercase()}%"
        var resultQuery =
            Reservation
                .find {
                    (ReservationTable.guestName.lowerCase() like searchTerm) or
                        (ReservationTable.guestPhone.lowerCase() like searchTerm)
                }.orderBy(
                    ReservationTable.reservationDate to SortOrder.DESC,
                    ReservationTable.reservationTime to SortOrder.ASC,
                )

        if (offset > 0) {
            resultQuery = resultQuery.limit(count = limit ?: Int.MAX_VALUE).offset(offset.toLong())
        } else if (limit != null) {
            resultQuery = resultQuery.limit(count = limit)
        }

        return resultQuery.toList()
    }
}
