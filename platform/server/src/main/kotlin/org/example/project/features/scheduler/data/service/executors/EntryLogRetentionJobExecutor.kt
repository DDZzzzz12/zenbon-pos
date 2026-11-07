package org.example.project.features.scheduler.data.service.executors

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.features.audit.data.service.EntryLogRetentionJob
import org.example.project.features.scheduler.domain.service.ScheduledJobExecutor

class EntryLogRetentionJobExecutor(
    private val retentionJob: EntryLogRetentionJob,
    private val retentionDays: Int = 90,
) : ScheduledJobExecutor {
    override val type = ScheduledJobType.ENTRY_LOG_RETENTION
    override val historyRetention = 30
    override val labelKey = "SCHEDULER_JOB_TYPE_ENTRY_LOG_RETENTION"

    override suspend fun execute(parametersJson: String): String {
        val pruned = retentionJob.pruneOlderThanDays(retentionDays)
        return "pruned $pruned entry_log rows older than $retentionDays days"
    }

    override fun validateParameters(parametersJson: String) { /* config-driven, no params */ }

    override fun parameterSchema(): JsonElement = JsonObject(emptyMap())
}
