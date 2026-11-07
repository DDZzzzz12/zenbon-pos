/**
 * Work schedule entity class for managing employee schedules.
 *
 * Manages weekly work schedules for employees including start/end times,
 * break durations, and scheduling information for each day of the week.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.employee.domain.models

import org.example.project.features.employee.database.tables.WorkSchedules
import org.jetbrains.exposed.v1.core.dao.id.*
import org.jetbrains.exposed.v1.dao.*
import org.jetbrains.exposed.v1.dao.java.*
import java.util.UUID

/**
 * Entity representing an employee's work schedule for a specific day.
 *
 * Stores scheduling information including work hours and break duration.
 * Multiple records can exist for different days of the week.
 */
class WorkSchedule(
    id: EntityID<UUID>,
) : UUIDEntity(id) {
    companion object : UUIDEntityClass<WorkSchedule>(WorkSchedules)

    /** Reference to the employee this schedule belongs to */
    var employee by Employee referencedOn WorkSchedules.employeeId

    /** Day of the week (1=Monday, 7=Sunday) */
    var dayOfWeek by WorkSchedules.dayOfWeek

    /** Start time in HH:MM format (e.g., "09:00") */
    var startTime by WorkSchedules.startTime

    /** End time in HH:MM format (e.g., "17:00") */
    var endTime by WorkSchedules.endTime

    /** Break duration in minutes */
    var breakDuration by WorkSchedules.breakDuration

    /** When this schedule was created */
    var createdAt by WorkSchedules.createdAt

    /** When this schedule was last updated */
    var updatedAt by WorkSchedules.updatedAt
}
