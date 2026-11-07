package org.example.project.features.timetracking.domain.repository

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.timetracking.TimeEntryStatus
import org.example.project.features.timetracking.domain.models.TimeEntry
import java.util.UUID

interface TimeEntryRepository {
    fun findById(id: UUID): TimeEntry?

    fun findActiveByEmployeeId(employeeId: UUID): TimeEntry?

    fun findAllActive(): List<TimeEntry>

    fun findByEmployeeAndMonth(
        employeeId: UUID,
        year: Int,
        month: Int,
    ): List<TimeEntry>

    fun findByMonth(
        year: Int,
        month: Int,
    ): List<TimeEntry>

    fun create(
        employeeId: UUID,
        date: LocalDate,
        clockIn: LocalDateTime,
        manualEntry: Boolean,
        costCenterId: UUID?,
        comment: String?,
    ): TimeEntry

    fun updateClockOut(
        id: UUID,
        clockOut: LocalDateTime,
    ): TimeEntry?

    fun update(
        id: UUID,
        clockIn: LocalDateTime?,
        clockOut: LocalDateTime?,
        costCenterId: UUID?,
        comment: String?,
    ): TimeEntry?

    fun updateStatus(
        id: UUID,
        status: TimeEntryStatus,
    ): TimeEntry?

    fun updateStatusByEmployeeAndMonth(
        employeeId: UUID,
        year: Int,
        month: Int,
        status: TimeEntryStatus,
    ): Int

    fun delete(id: UUID): Boolean
}
