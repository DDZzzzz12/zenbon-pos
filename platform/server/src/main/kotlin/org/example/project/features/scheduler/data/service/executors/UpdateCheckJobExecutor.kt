package org.example.project.features.scheduler.data.service.executors

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.features.scheduler.domain.service.ScheduledJobExecutor
import org.example.project.features.update.domain.service.UpdateService

/**
 * Runs the nightly update check on the existing scheduler.
 *
 * A refused preflight comes back as a **SUCCESS** whose message begins `skipped: `, not as
 * [org.example.project.core.domain.enums.scheduler.JobStatus.SKIPPED]. An executor cannot produce
 * that status: `SchedulerEngine` writes SKIPPED only for a run it never started (a previous
 * execution still running, or a run missed while the server was down), and turns a normal return
 * into SUCCESS and a throw into FAILED. Throwing to colour the row red would be worse than
 * inaccurate -- a shop that closes late would fail this job every night, and a job that always
 * fails is a job nobody reads.
 */
class UpdateCheckJobExecutor(
    private val updateService: UpdateService,
) : ScheduledJobExecutor {
    override val type = ScheduledJobType.UPDATE_CHECK
    override val historyRetention = 30
    override val labelKey = "SCHEDULER_JOB_TYPE_UPDATE_CHECK"

    override suspend fun execute(parametersJson: String): String = updateService.runScheduledCheck()

    override fun validateParameters(parametersJson: String) { /* config-driven, no params */ }

    override fun parameterSchema(): JsonElement = JsonObject(emptyMap())
}
