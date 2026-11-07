package org.example.project.features.timetracking.domain.models

import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.timetracking.database.tables.TimeBreakTable
import org.example.project.features.timetracking.database.tables.TimeEntryTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import java.util.UUID

class TimeEntry(
    id: EntityID<UUID>,
) : UUIDEntity(id) {
    companion object : UUIDEntityClass<TimeEntry>(TimeEntryTable)

    var employee by Employee referencedOn TimeEntryTable.employeeId
    var date by TimeEntryTable.date
    var clockIn by TimeEntryTable.clockIn
    var clockOut by TimeEntryTable.clockOut
    var manualEntry by TimeEntryTable.manualEntry
    var costCenter by CostCenter optionalReferencedOn TimeEntryTable.costCenterId
    var comment by TimeEntryTable.comment
    var status by TimeEntryTable.status
    var createdAt by TimeEntryTable.createdAt
    var updatedAt by TimeEntryTable.updatedAt

    val breaks by TimeBreak referrersOn TimeBreakTable.timeEntryId
}
