package org.example.project.features.scheduler.domain.service

import kotlinx.serialization.json.JsonElement
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.core.domain.enums.setting.RestoredPathSetting

/** Contract every schedulable job type must implement. */
interface ScheduledJobExecutor {
    val type: ScheduledJobType
    val historyRetention: Int
    val labelKey: String

    /**
     * The configured folders this job writes to. While any of them, restored from another
     * installation's backup, awaits review, `SchedulerEngine` skips the job instead of running it.
     */
    val restoredPathsWritten: Set<RestoredPathSetting> get() = emptySet()

    /**
     * Run the job. Returns a human-readable result message on success.
     * Throws [org.example.project.infrastructure.errors.PosException] on failure, and
     * [JobPartiallyFailedException] when only part of the work succeeded — never a success message
     * that merely mentions the failures.
     */
    suspend fun execute(parametersJson: String): String

    /**
     * Validate parameters; throws [org.example.project.infrastructure.errors.PosException]
     * (typically with [org.example.project.core.error.PosErrorCode.VALIDATION_FAILED]) when invalid.
     */
    fun validateParameters(parametersJson: String)

    fun parameterSchema(): JsonElement
}
