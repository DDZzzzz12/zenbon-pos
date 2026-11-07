package org.example.project.features.scheduler.data.service

import org.example.project.core.domain.enums.scheduler.JobFrequency
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.core.domain.enums.setting.DatevAutoCadence
import org.example.project.features.scheduler.domain.models.ScheduledJob
import org.example.project.features.scheduler.domain.repository.ScheduledJobRepository
import org.example.project.features.setup.domain.service.ConfigurationService
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Upserts the six built-in scheduled job rows (BACKUP, DATEV_AUTO_EXPORT,
 * ENTRY_LOG_RETENTION, CONTRACT_INSTALMENTS, REVERSAL_SWEEP, UPDATE_CHECK) from the current shop
 * configuration.
 * Invoked at server startup and after backup / DATEV config writes.
 */
class BuiltInJobsSeeder(
    private val jobs: ScheduledJobRepository,
    private val config: ConfigurationService,
    private val nextRun: NextRunCalculator,
    private val shopId: Long = 1L,
) {
    companion object {
        /**
         * Job types whose `enabled` flag is derived from shop configuration and
         * re-synced on every seed. They cannot be toggled via the scheduled-jobs
         * API — the backup / DATEV settings pages are the single source of truth.
         */
        val CONFIG_MANAGED_TYPES = setOf(ScheduledJobType.BACKUP, ScheduledJobType.DATEV_AUTO_EXPORT)
    }

    private val timezone: String = "Europe/Berlin"

    suspend fun seedAll() {
        seedBackup()
        seedDatev()
        seedEntryLogRetention()
        seedContractInstalments()
        seedReversalSweep()
        seedUpdateCheck()
    }

    suspend fun seedBackup() {
        val cfg = config.getBackupConfig(shopId)
        val frequency =
            when (cfg.cadence.name) {
                "WEEKLY" -> JobFrequency.WEEKLY
                else -> JobFrequency.DAILY
            }
        upsertByType(
            type = ScheduledJobType.BACKUP,
            name = "Auto Backup",
            enabled = cfg.autoEnabled,
            frequency = frequency,
            timeOfDay = "%02d:00".format(cfg.runHour),
        )
    }

    suspend fun seedDatev() {
        val cfg = config.getDatevXmlConfig(shopId)
        val cadences = listOf(cfg.kassenbuchCadence, cfg.apLedgerCadence, cfg.buchungsstapelCadence)
        val active = cadences.any { it != DatevAutoCadence.OFF }
        // Most-frequent cadence wins: WEEKLY > MONTHLY; DAILY only used as
        // a dormant placeholder when nothing is active (enabled will be false).
        val frequency =
            when {
                cadences.any { it == DatevAutoCadence.WEEKLY } -> JobFrequency.WEEKLY
                cadences.any { it == DatevAutoCadence.MONTHLY } -> JobFrequency.MONTHLY
                else -> JobFrequency.DAILY
            }
        upsertByType(
            type = ScheduledJobType.DATEV_AUTO_EXPORT,
            name = "Auto DATEV Export",
            enabled = cfg.enabled && active,
            frequency = frequency,
            timeOfDay = "%02d:00".format(cfg.runHour),
        )
    }

    fun seedEntryLogRetention() {
        // Retention is opt-in by default: entry_log doubles as the audit log and may be
        // subject to legal retention obligations (German fiscal POS — TSE/DSFinV-K/GoBD).
        // The change-feed sync does not require pruning to function, so an operator must
        // enable this deliberately via PUT /api/scheduled-jobs/{id}/enabled. Reseeding
        // must therefore never reset an operator's choice — enabled=false applies only
        // on first insert.
        upsertByType(
            type = ScheduledJobType.ENTRY_LOG_RETENTION,
            name = "Entry Log Retention",
            enabled = false,
            frequency = JobFrequency.DAILY,
            timeOfDay = "03:00",
            preserveEnabledOnUpdate = true,
        )
    }

    fun seedContractInstalments() {
        // Enabled by default, unlike entry-log retention: that job deletes data and is opt-in for
        // legal reasons, while this one only materialises rows the operator already agreed to by
        // creating a contract. A contract that silently generates nothing because a job was left
        // off would be worse than useless. An operator may still switch it off, and reseeding must
        // not undo that — preserveEnabledOnUpdate keeps enabled=true to the first insert.
        upsertByType(
            type = ScheduledJobType.CONTRACT_INSTALMENTS,
            name = "Contract Instalments",
            enabled = true,
            frequency = JobFrequency.DAILY,
            timeOfDay = "02:00",
            preserveEnabledOnUpdate = true,
        )
    }

    fun seedReversalSweep() {
        // Enabled by default, unlike entry-log retention: that job deletes data an operator may be
        // legally required to keep and is therefore opt-in, while this one deletes nothing anybody
        // chose — it corrects a state the bank already changed. A returned direct debit that
        // silently stays "paid" is the failure this whole slice exists to prevent, and it is
        // invisible: the money came back, the supplier is still owed, and the open-item list says
        // everything is settled. An operator may still switch it off, and preserveEnabledOnUpdate
        // keeps enabled=true to the first insert so reseeding does not undo that choice.
        upsertByType(
            type = ScheduledJobType.REVERSAL_SWEEP,
            name = "Reversal Sweep",
            enabled = true,
            frequency = JobFrequency.DAILY,
            timeOfDay = "04:00",
            preserveEnabledOnUpdate = true,
        )
    }

    fun seedUpdateCheck() {
        // Enabled by default: *checking* for a release is harmless -- it reads one GitHub endpoint
        // and, at most, stages a verified artifact on disk. Installing is the dangerous half and is
        // a separate, opt-in decision (update_settings.auto_apply, off by default), so an operator
        // who never touches this still gets told that an update exists instead of silently running
        // an old build for a year. preserveEnabledOnUpdate keeps enabled=true to the first insert so
        // switching it off survives a restart.
        //
        // Seeded here rather than by a Flyway migration because `scheduled_jobs` is created by
        // Exposed's SchemaUtils, which runs AFTER Flyway: an INSERT in a migration hits a table that
        // does not exist yet on a fresh install. V72 makes the same point and deletes the rows for
        // this seeder to rebuild.
        //
        // 03:00 Europe/Berlin: after the 02:00 contract instalments, before the 04:00 reversal
        // sweep, and inside the window UpdatePreflight expects a shop to be idle in.
        upsertByType(
            type = ScheduledJobType.UPDATE_CHECK,
            name = "Update Check",
            enabled = true,
            frequency = JobFrequency.DAILY,
            timeOfDay = "03:00",
            preserveEnabledOnUpdate = true,
        )
    }

    private fun upsertByType(
        type: ScheduledJobType,
        name: String,
        enabled: Boolean,
        frequency: JobFrequency,
        timeOfDay: String,
        preserveEnabledOnUpdate: Boolean = false,
    ) {
        // Pick safe schedule shape so NextRunCalculator can compute a real instant
        // even for dormant rows (frequency may be MONTHLY/WEEKLY while disabled).
        val dayOfWeek = if (frequency == JobFrequency.WEEKLY) 1 else null
        val dayOfMonth = if (frequency == JobFrequency.MONTHLY) 1 else null
        val nextRunAt =
            nextRun.next(
                frequency = frequency,
                timeOfDay = timeOfDay,
                dayOfWeek = dayOfWeek,
                dayOfMonth = dayOfMonth,
                zone = ZoneId.of(timezone),
                reference = Instant.now(),
            )
        val existing = jobs.findByType(type)
        val now = Instant.now()
        if (existing == null) {
            jobs.create(
                ScheduledJob(
                    id = UUID.randomUUID(),
                    name = name,
                    type = type,
                    enabled = enabled,
                    frequency = frequency,
                    timeOfDay = timeOfDay,
                    dayOfWeek = dayOfWeek,
                    dayOfMonth = dayOfMonth,
                    parametersJson = "{}",
                    timezone = timezone,
                    nextRunAt = nextRunAt,
                    lastRunAt = null,
                    lastStatus = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        } else {
            jobs.update(
                existing.copy(
                    name = name,
                    enabled = if (preserveEnabledOnUpdate) existing.enabled else enabled,
                    frequency = frequency,
                    timeOfDay = timeOfDay,
                    dayOfWeek = dayOfWeek,
                    dayOfMonth = dayOfMonth,
                    timezone = timezone,
                    nextRunAt = nextRunAt,
                    updatedAt = now,
                ),
            )
        }
    }
}
