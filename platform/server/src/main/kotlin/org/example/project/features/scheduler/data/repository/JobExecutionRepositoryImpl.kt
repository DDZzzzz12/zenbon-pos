package org.example.project.features.scheduler.data.repository

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.enums.scheduler.JobStatus
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.core.domain.enums.scheduler.TriggerSource
import org.example.project.features.scheduler.database.tables.JobExecutionEntity
import org.example.project.features.scheduler.database.tables.JobExecutions
import org.example.project.features.scheduler.database.tables.ScheduledJobs
import org.example.project.features.scheduler.domain.models.JobExecution
import org.example.project.features.scheduler.domain.repository.JobExecutionRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.util.UUID

@OptIn(kotlin.time.ExperimentalTime::class)
class JobExecutionRepositoryImpl : JobExecutionRepository {
    override fun insertRunning(
        jobId: UUID,
        triggerSource: TriggerSource,
        startedAt: Instant,
    ): JobExecution {
        val e =
            JobExecutionEntity.new(UUID.randomUUID()) {
                this.jobId = EntityID(jobId, ScheduledJobs)
                this.startedAt = startedAt.toKotlinxLocalDateTime()
                this.finishedAt = null
                this.status = JobStatus.RUNNING.name
                this.triggerSource = triggerSource.name
                this.resultMessage = null
                this.errorMessage = null
            }
        return e.toModel()
    }

    override fun insertSkipped(
        jobId: UUID,
        triggerSource: TriggerSource,
        startedAt: Instant,
        reason: String,
    ): JobExecution {
        val e =
            JobExecutionEntity.new(UUID.randomUUID()) {
                this.jobId = EntityID(jobId, ScheduledJobs)
                this.startedAt = startedAt.toKotlinxLocalDateTime()
                this.finishedAt = startedAt.toKotlinxLocalDateTime()
                this.status = JobStatus.SKIPPED.name
                this.triggerSource = triggerSource.name
                this.resultMessage = null
                this.errorMessage = reason
            }
        return e.toModel()
    }

    override fun markFinished(
        executionId: UUID,
        finishedAt: Instant,
        status: JobStatus,
        resultMessage: String?,
        errorMessage: String?,
    ) {
        val e =
            JobExecutionEntity.findById(executionId)
                ?: throw NoSuchElementException("Execution not found: $executionId")
        e.finishedAt = finishedAt.toKotlinxLocalDateTime()
        e.status = status.name
        e.resultMessage = resultMessage
        e.errorMessage = errorMessage
    }

    override fun hasRunningFor(jobId: UUID): Boolean =
        JobExecutionEntity
            .find {
                (JobExecutions.jobId eq jobId) and (JobExecutions.status eq JobStatus.RUNNING.name)
            }.limit(1)
            .any()

    override fun countRunningExcludingType(excluding: ScheduledJobType): Int {
        val ownJobIds =
            ScheduledJobs
                .select(ScheduledJobs.id)
                .where { ScheduledJobs.type eq excluding.name }
                .map { it[ScheduledJobs.id].value }
        val running = JobExecutions.status eq JobStatus.RUNNING.name
        val predicate =
            if (ownJobIds.isEmpty()) running else running and (JobExecutions.jobId notInList ownJobIds)
        return JobExecutions
            .selectAll()
            .where(predicate)
            .count()
            .toInt()
    }

    override fun markOrphanRunningAsFailed(reason: String): Int {
        val orphans = JobExecutionEntity.find { JobExecutions.status eq JobStatus.RUNNING.name }.toList()
        val nowKt = Instant.now().toKotlinxLocalDateTime()
        orphans.forEach {
            it.status = JobStatus.FAILED.name
            it.finishedAt = nowKt
            it.errorMessage = reason
        }
        return orphans.size
    }

    override fun recentForJob(
        jobId: UUID,
        limit: Int,
    ): List<JobExecution> =
        JobExecutionEntity
            .find { JobExecutions.jobId eq jobId }
            .orderBy(JobExecutions.startedAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toModel() }

    override fun pageForJob(
        jobId: UUID,
        limit: Int,
        offset: Int,
    ): List<JobExecution> =
        JobExecutionEntity
            .find { JobExecutions.jobId eq jobId }
            .orderBy(JobExecutions.startedAt to SortOrder.DESC)
            .limit(limit)
            .offset(offset.toLong())
            .map { it.toModel() }

    override fun countForJob(jobId: UUID): Long = JobExecutionEntity.find { JobExecutions.jobId eq jobId }.count()

    override fun pruneExceeding(
        jobId: UUID,
        keep: Int,
    ): Int {
        val keepIds =
            JobExecutionEntity
                .find { JobExecutions.jobId eq jobId }
                .orderBy(JobExecutions.startedAt to SortOrder.DESC)
                .limit(keep)
                .map { it.id }
        return JobExecutions.deleteWhere {
            (JobExecutions.jobId eq jobId) and (JobExecutions.id notInList keepIds)
        }
    }

    private fun JobExecutionEntity.toModel() =
        JobExecution(
            id = id.value,
            jobId = jobId.value,
            startedAt = startedAt.toJavaInstant(),
            finishedAt = finishedAt?.toJavaInstant(),
            status = JobStatus.valueOf(status),
            triggerSource = TriggerSource.valueOf(triggerSource),
            resultMessage = resultMessage,
            errorMessage = errorMessage,
        )

    // Conversion helpers

    private fun Instant.toKotlinxLocalDateTime(): LocalDateTime =
        kotlinx.datetime.Instant
            .fromEpochMilliseconds(toEpochMilli())
            .toLocalDateTime(TimeZone.UTC)

    private fun LocalDateTime.toJavaInstant(): java.time.Instant =
        java.time.Instant.ofEpochMilli(
            toInstant(TimeZone.UTC).toEpochMilliseconds(),
        )
}
