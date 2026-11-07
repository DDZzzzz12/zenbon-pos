package org.example.project.features.scheduler.database.tables

import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import org.jetbrains.exposed.v1.datetime.datetime
import java.util.UUID

object ScheduledJobs : UUIDTable(name = "scheduled_jobs", columnName = "id") {
    val name = text("name")
    val type = text("type")
    val enabled = bool("enabled").default(true)
    val frequency = text("frequency")
    val timeOfDay = text("time_of_day")
    val dayOfWeek = integer("day_of_week").nullable()
    val dayOfMonth = integer("day_of_month").nullable()
    val parametersJson = text("parameters_json").default("{}")
    val timezone = text("timezone").default("Europe/Berlin")
    val nextRunAt = datetime("next_run_at")
    val lastRunAt = datetime("last_run_at").nullable()
    val lastStatus = text("last_status").nullable()
    val createdAt = datetime("created_at").clientDefault { currentTimeStamp() }
    val updatedAt = datetime("updated_at").clientDefault { currentTimeStamp() }
}

class ScheduledJobEntity(
    id: EntityID<UUID>,
) : UUIDEntity(id) {
    companion object : UUIDEntityClass<ScheduledJobEntity>(ScheduledJobs)

    var name by ScheduledJobs.name
    var type by ScheduledJobs.type
    var enabled by ScheduledJobs.enabled
    var frequency by ScheduledJobs.frequency
    var timeOfDay by ScheduledJobs.timeOfDay
    var dayOfWeek by ScheduledJobs.dayOfWeek
    var dayOfMonth by ScheduledJobs.dayOfMonth
    var parametersJson by ScheduledJobs.parametersJson
    var timezone by ScheduledJobs.timezone
    var nextRunAt by ScheduledJobs.nextRunAt
    var lastRunAt by ScheduledJobs.lastRunAt
    var lastStatus by ScheduledJobs.lastStatus
    var createdAt by ScheduledJobs.createdAt
    var updatedAt by ScheduledJobs.updatedAt
}
