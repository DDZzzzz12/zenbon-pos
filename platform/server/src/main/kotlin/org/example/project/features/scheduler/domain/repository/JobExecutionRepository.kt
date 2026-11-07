package org.example.project.features.scheduler.domain.repository

import org.example.project.core.domain.enums.scheduler.JobStatus
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.core.domain.enums.scheduler.TriggerSource
import org.example.project.features.scheduler.domain.models.JobExecution
import java.time.Instant
import java.util.UUID

interface JobExecutionRepository {
    fun insertRunning(
        jobId: UUID,
        triggerSource: TriggerSource,
        startedAt: Instant,
    ): JobExecution

    fun insertSkipped(
        jobId: UUID,
        triggerSource: TriggerSource,
        startedAt: Instant,
        reason: String,
    ): JobExecution

    fun markFinished(
        executionId: UUID,
        finishedAt: Instant,
        status: JobStatus,
        resultMessage: String?,
        errorMessage: String?,
    )

    fun hasRunningFor(jobId: UUID): Boolean

    /**
     * How many executions are RUNNING right now, ignoring every execution of jobs of [excluding].
     *
     * The exclusion is the point. A job that asks "is anything else running?" from inside its own
     * execution always sees at least one -- its own row, which the engine inserted as RUNNING
     * before calling the executor. Counting it would make the update preflight refuse every night,
     * silently and plausibly, with `JOB_RUNNING`.
     */
    fun countRunningExcludingType(excluding: ScheduledJobType): Int

    fun markOrphanRunningAsFailed(reason: String): Int

    fun recentForJob(
        jobId: UUID,
        limit: Int,
    ): List<JobExecution>

    fun pageForJob(
        jobId: UUID,
        limit: Int,
        offset: Int,
    ): List<JobExecution>

    fun countForJob(jobId: UUID): Long

    fun pruneExceeding(
        jobId: UUID,
        keep: Int,
    ): Int
}
