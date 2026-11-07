package org.example.project.features.scheduler.database.tables

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.dao.java.UUIDEntity
import org.jetbrains.exposed.v1.dao.java.UUIDEntityClass
import org.jetbrains.exposed.v1.datetime.datetime
import java.util.UUID

object JobExecutions : UUIDTable(name = "job_executions", columnName = "id") {
    val jobId = reference("job_id", ScheduledJobs)
    val startedAt = datetime("started_at")
    val finishedAt = datetime("finished_at").nullable()
    val status = text("status")
    val triggerSource = text("trigger_source")
    val resultMessage = text("result_message").nullable()
    val errorMessage = text("error_message").nullable()
}

class JobExecutionEntity(
    id: EntityID<UUID>,
) : UUIDEntity(id) {
    companion object : UUIDEntityClass<JobExecutionEntity>(JobExecutions)

    var jobId by JobExecutions.jobId
    var startedAt by JobExecutions.startedAt
    var finishedAt by JobExecutions.finishedAt
    var status by JobExecutions.status
    var triggerSource by JobExecutions.triggerSource
    var resultMessage by JobExecutions.resultMessage
    var errorMessage by JobExecutions.errorMessage
}
