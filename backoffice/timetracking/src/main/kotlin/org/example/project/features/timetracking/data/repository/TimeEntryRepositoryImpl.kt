package org.example.project.features.timetracking.data.repository

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.timetracking.TimeEntryStatus
import org.example.project.features.timetracking.database.tables.TimeEntryTable
import org.example.project.features.timetracking.domain.models.CostCenter
import org.example.project.features.timetracking.domain.models.TimeEntry
import org.example.project.features.timetracking.domain.repository.TimeEntryRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

class TimeEntryRepositoryImpl : TimeEntryRepository {
    override fun findById(id: UUID): TimeEntry? = TimeEntry.findById(id)

    override fun findActiveByEmployeeId(employeeId: UUID): TimeEntry? =
        TimeEntry
            .find {
                (TimeEntryTable.employeeId eq employeeId) and (TimeEntryTable.clockOut.isNull())
            }.firstOrNull()

    override fun findAllActive(): List<TimeEntry> = TimeEntry.find { TimeEntryTable.clockOut.isNull() }.toList()

    override fun findByEmployeeAndMonth(
        employeeId: UUID,
        year: Int,
        month: Int,
    ): List<TimeEntry> {
        val startDate = LocalDate(year, month, 1)
        val endDate = if (month == 12) LocalDate(year + 1, 1, 1) else LocalDate(year, month + 1, 1)
        return TimeEntry
            .find {
                (TimeEntryTable.employeeId eq employeeId) and
                    (TimeEntryTable.date greaterEq startDate) and
                    (TimeEntryTable.date less endDate)
            }.sortedBy { it.date }
            .toList()
    }

    override fun findByMonth(
        year: Int,
        month: Int,
    ): List<TimeEntry> {
        val startDate = LocalDate(year, month, 1)
        val endDate = if (month == 12) LocalDate(year + 1, 1, 1) else LocalDate(year, month + 1, 1)
        return TimeEntry
            .find {
                (TimeEntryTable.date greaterEq startDate) and (TimeEntryTable.date less endDate)
            }.sortedBy { it.date }
            .toList()
    }

    override fun create(
        employeeId: UUID,
        date: LocalDate,
        clockIn: LocalDateTime,
        manualEntry: Boolean,
        costCenterId: UUID?,
        comment: String?,
    ): TimeEntry =
        TimeEntry.new {
            this.employee = org.example.project.features.employee.domain.models.Employee[employeeId]
            this.date = date
            this.clockIn = clockIn
            this.manualEntry = manualEntry
            this.costCenter = costCenterId?.let { CostCenter[it] }
            this.comment = comment
            this.status = TimeEntryStatus.DRAFT
            this.createdAt = currentTimeStamp()
            this.updatedAt = currentTimeStamp()
        }

    override fun updateClockOut(
        id: UUID,
        clockOut: LocalDateTime,
    ): TimeEntry? {
        val entry = TimeEntry.findById(id) ?: return null
        entry.clockOut = clockOut
        entry.updatedAt = currentTimeStamp()
        return entry
    }

    override fun update(
        id: UUID,
        clockIn: LocalDateTime?,
        clockOut: LocalDateTime?,
        costCenterId: UUID?,
        comment: String?,
    ): TimeEntry? {
        val entry = TimeEntry.findById(id) ?: return null
        clockIn?.let { entry.clockIn = it }
        clockOut?.let { entry.clockOut = it }
        costCenterId?.let { entry.costCenter = CostCenter[it] }
        comment?.let { entry.comment = it }
        entry.updatedAt = currentTimeStamp()
        return entry
    }

    override fun updateStatus(
        id: UUID,
        status: TimeEntryStatus,
    ): TimeEntry? {
        val entry = TimeEntry.findById(id) ?: return null
        entry.status = status
        entry.updatedAt = currentTimeStamp()
        return entry
    }

    override fun updateStatusByEmployeeAndMonth(
        employeeId: UUID,
        year: Int,
        month: Int,
        status: TimeEntryStatus,
    ): Int {
        val startDate = LocalDate(year, month, 1)
        val endDate = if (month == 12) LocalDate(year + 1, 1, 1) else LocalDate(year, month + 1, 1)
        return TimeEntryTable.update({
            (TimeEntryTable.employeeId eq employeeId) and
                (TimeEntryTable.date greaterEq startDate) and
                (TimeEntryTable.date less endDate)
        }) {
            it[TimeEntryTable.status] = status
            it[updatedAt] = currentTimeStamp()
        }
    }

    override fun delete(id: UUID): Boolean {
        val entry = TimeEntry.findById(id) ?: return false
        entry.delete()
        return true
    }
}
