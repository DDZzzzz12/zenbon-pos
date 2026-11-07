package org.example.project.features.scheduler.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.scheduler.SchedulerDto
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.scheduler.domain.models.JobExecution
import org.example.project.features.scheduler.domain.models.ScheduledJob
import org.example.project.features.scheduler.domain.repository.JobExecutionRepository
import org.example.project.features.scheduler.domain.repository.ScheduledJobRepository
import org.example.project.features.scheduler.domain.service.ScheduledJobExecutor
import org.example.project.features.scheduler.domain.service.ScheduledJobService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class ScheduledJobServiceImpl(
    private val jobRepo: ScheduledJobRepository,
    private val execRepo: JobExecutionRepository,
    private val executors: List<ScheduledJobExecutor>,
    private val engine: SchedulerEngine,
    private val nextRun: NextRunCalculator = NextRunCalculator(),
) : ScheduledJobService {
    private val recentExecutionsLimit = 20

    override suspend fun list(): List<SchedulerDto.ScheduledJobResponseDto> =
        dbQuery {
            jobRepo.findAll().map { it.toDto() }
        }

    override suspend fun getDetail(id: UUID): SchedulerDto.ScheduledJobDetailDto =
        dbQuery {
            val job = jobRepo.findById(id) ?: throw jobNotFound(id)
            val recent = execRepo.recentForJob(id, recentExecutionsLimit).map { it.toDto() }
            SchedulerDto.ScheduledJobDetailDto(job.toDto(), recent)
        }

    override suspend fun triggerManualRun(id: UUID): SchedulerDto.JobExecutionResponseDto {
        val job = dbQuery { jobRepo.findById(id) } ?: throw jobNotFound(id)
        val execution = engine.runNow(job)
        return execution.toDto()
    }

    override suspend fun setEnabled(
        id: UUID,
        enabled: Boolean,
    ): SchedulerDto.ScheduledJobResponseDto =
        dbQuery {
            val job = jobRepo.findById(id) ?: throw jobNotFound(id)
            if (job.type in BuiltInJobsSeeder.CONFIG_MANAGED_TYPES) throw configManagedJob(job.type)
            if (job.enabled == enabled) return@dbQuery job.toDto()
            val now = Instant.now()
            // Re-anchor the schedule when enabling so a long-dormant job doesn't fire
            // immediately off a stale next_run_at in the past.
            val nextRunAt =
                if (enabled) {
                    val zone = runCatching { ZoneId.of(job.timezone) }.getOrDefault(ZoneId.of("Europe/Berlin"))
                    nextRun.next(job.frequency, job.timeOfDay, job.dayOfWeek, job.dayOfMonth, zone, now)
                } else {
                    job.nextRunAt
                }
            jobRepo.update(job.copy(enabled = enabled, nextRunAt = nextRunAt, updatedAt = now)).toDto()
        }

    override suspend fun listExecutions(
        id: UUID,
        limit: Int,
        offset: Int,
    ): SchedulerDto.JobExecutionPageDto =
        dbQuery {
            val safeLimit = limit.coerceIn(1, 200)
            val safeOffset = offset.coerceAtLeast(0)
            val items = execRepo.pageForJob(id, safeLimit, safeOffset).map { it.toDto() }
            val total = execRepo.countForJob(id)
            SchedulerDto.JobExecutionPageDto(items, total, safeLimit, safeOffset)
        }

    override suspend fun listTypes(): List<SchedulerDto.JobTypeInfoDto> =
        executors.map {
            SchedulerDto.JobTypeInfoDto(
                type = it.type,
                labelKey = it.labelKey,
                historyRetention = it.historyRetention,
                parameterSchema = it.parameterSchema().toString(),
            )
        }

    // ---------- helpers ----------

    private fun ScheduledJob.toDto() =
        SchedulerDto.ScheduledJobResponseDto(
            id = id.toString(),
            name = name,
            type = type,
            enabled = enabled,
            frequency = frequency,
            timeOfDay = timeOfDay,
            dayOfWeek = dayOfWeek,
            dayOfMonth = dayOfMonth,
            parametersJson = parametersJson,
            timezone = timezone,
            nextRunAt = nextRunAt.toEpochMilli(),
            lastRunAt = lastRunAt?.toEpochMilli(),
            lastStatus = lastStatus,
            createdAt = createdAt.toEpochMilli(),
            updatedAt = updatedAt.toEpochMilli(),
        )

    private fun JobExecution.toDto() =
        SchedulerDto.JobExecutionResponseDto(
            id = id.toString(),
            jobId = jobId.toString(),
            startedAt = startedAt.toEpochMilli(),
            finishedAt = finishedAt?.toEpochMilli(),
            status = status,
            triggerSource = triggerSource,
            resultMessage = resultMessage,
            errorMessage = errorMessage,
        )

    // --- PosException helpers ---

    private fun jobNotFound(id: UUID) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Scheduled job $id not found",
        )

    private fun configManagedJob(type: ScheduledJobType) =
        PosException(
            PosErrorCode.SCHEDULED_JOB_CONFIG_MANAGED,
            HttpStatusCode.Conflict,
            "Job type $type is managed by its configuration page (backup / DATEV settings) and cannot be toggled here",
        )
}
