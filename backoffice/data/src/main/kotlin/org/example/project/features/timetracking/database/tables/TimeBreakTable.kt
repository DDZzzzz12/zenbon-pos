package org.example.project.features.timetracking.database.tables

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.datetime

object TimeBreakTable : UUIDTable("time_break") {
    val timeEntryId = reference("time_entry_id", TimeEntryTable)
    val breakStart = datetime("break_start")
    val breakEnd = datetime("break_end").nullable()
}
