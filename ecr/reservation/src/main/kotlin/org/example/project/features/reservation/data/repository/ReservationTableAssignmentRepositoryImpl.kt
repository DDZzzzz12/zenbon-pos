@file:OptIn(kotlin.time.ExperimentalTime::class)

package org.example.project.features.reservation.data.repository

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.enums.reservation.ReservationStatus
import org.example.project.features.reservation.database.tables.ReservationTable
import org.example.project.features.reservation.database.tables.ReservationTableAssignmentTable
import org.example.project.features.reservation.domain.models.ReservationTableAssignment
import org.example.project.features.reservation.domain.repository.ReservationTableAssignmentRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Clock

class ReservationTableAssignmentRepositoryImpl : ReservationTableAssignmentRepository {
    private val logger = LoggerFactory.getLogger(ReservationTableAssignmentRepositoryImpl::class.java)

    override fun getByReservationId(reservationId: Long): List<ReservationTableAssignment> {
        logger.info("ReservationTableAssignmentRepositoryImpl.getByReservationId: reservationId=$reservationId")
        return ReservationTableAssignment
            .find {
                ReservationTableAssignmentTable.reservationId eq reservationId
            }.toList()
    }

    override fun getOccupiedTableIds(
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime,
        excludeReservationId: Long?,
        defaultDurationMinutes: Int,
    ): Set<Long> {
        logger.info("ReservationTableAssignmentRepositoryImpl.getOccupiedTableIds: date=$date, window=$startTime-$endTime")

        val excludeStatuses = listOf(ReservationStatus.CANCELLED, ReservationStatus.NO_SHOW, ReservationStatus.COMPLETED)

        val baseQuery =
            ReservationTableAssignmentTable
                .innerJoin(ReservationTable, { ReservationTableAssignmentTable.reservationId }, { ReservationTable.id })
                .select(
                    ReservationTableAssignmentTable.tableId,
                    ReservationTable.reservationTime,
                    ReservationTable.durationMinutes,
                ).where {
                    (ReservationTable.reservationDate eq date) and
                        (ReservationTable.status notInList excludeStatuses)
                }

        if (excludeReservationId != null) {
            baseQuery.andWhere { ReservationTableAssignmentTable.reservationId neq excludeReservationId }
        }

        return baseQuery
            .mapNotNull { row ->
                val resTime = row[ReservationTable.reservationTime]
                val duration = row[ReservationTable.durationMinutes] ?: defaultDurationMinutes
                val resEndTime = addMinutes(resTime, duration)

                val overlaps = resTime < endTime && resEndTime > startTime
                if (overlaps) row[ReservationTableAssignmentTable.tableId].value else null
            }.toSet()
    }

    override fun create(
        reservationId: Long,
        tableId: Long,
        groupNumber: Int,
        assignedBy: UUID,
    ): ReservationTableAssignment {
        logger.info("ReservationTableAssignmentRepositoryImpl.create: reservation=$reservationId, table=$tableId, group=$groupNumber")
        return ReservationTableAssignment.new {
            this.reservation = org.example.project.features.reservation.domain.models.Reservation
                .findById(reservationId)
                ?: throw NoSuchElementException("Reservation $reservationId not found")
            this.table = org.example.project.features.table.domain.models.RTable
                .findById(tableId)
                ?: throw NoSuchElementException("Table $tableId not found")
            this.groupNumber = groupNumber
            this.assignedByEmployee = org.example.project.features.employee.domain.models.Employee
                .findById(assignedBy)
                ?: throw NoSuchElementException("Employee $assignedBy not found")
            this.assignedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        }
    }

    override fun deleteByReservationId(reservationId: Long): Int {
        logger.info("ReservationTableAssignmentRepositoryImpl.deleteByReservationId: reservationId=$reservationId")
        return ReservationTableAssignmentTable.deleteWhere {
            ReservationTableAssignmentTable.reservationId eq reservationId
        }
    }

    override fun deleteByReservationAndTable(
        reservationId: Long,
        tableId: Long,
    ): Boolean {
        logger.info("ReservationTableAssignmentRepositoryImpl.deleteByReservationAndTable: reservation=$reservationId, table=$tableId")
        val count =
            ReservationTableAssignmentTable.deleteWhere {
                (ReservationTableAssignmentTable.reservationId eq reservationId) and
                    (ReservationTableAssignmentTable.tableId eq tableId)
            }
        return count > 0
    }

    private fun addMinutes(
        time: LocalTime,
        minutes: Int,
    ): LocalTime {
        val totalMinutes = time.hour * 60 + time.minute + minutes
        return if (totalMinutes >= 24 * 60) {
            LocalTime(23, 59)
        } else {
            LocalTime(totalMinutes / 60, totalMinutes % 60)
        }
    }
}
