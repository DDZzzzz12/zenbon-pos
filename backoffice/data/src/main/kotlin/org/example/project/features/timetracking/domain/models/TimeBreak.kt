package org.example.project.features.timetracking.domain.models

import org.example.project.features.timetracking.database.tables.TimeBreakTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import java.util.UUID

class TimeBreak(
    id: EntityID<UUID>,
) : UUIDEntity(id) {
    companion object : UUIDEntityClass<TimeBreak>(TimeBreakTable)

    var timeEntry by TimeEntry referencedOn TimeBreakTable.timeEntryId
    var breakStart by TimeBreakTable.breakStart
    var breakEnd by TimeBreakTable.breakEnd
}
