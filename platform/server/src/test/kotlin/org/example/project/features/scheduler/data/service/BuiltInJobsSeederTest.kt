package org.example.project.features.scheduler.data.service

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.backup.BackupCopyStatus
import org.example.project.core.domain.enums.scheduler.JobFrequency
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.core.domain.enums.setting.DatevAutoCadence
import org.example.project.features.scheduler.domain.models.ScheduledJob
import org.example.project.features.scheduler.domain.repository.ScheduledJobRepository
import org.example.project.features.setup.domain.service.ConfigurationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@DisplayName("BuiltInJobsSeeder")
class BuiltInJobsSeederTest {
    /** In-memory fake repository keyed by UUID. */
    private class FakeScheduledJobRepository : ScheduledJobRepository {
        val rows = mutableMapOf<UUID, ScheduledJob>()

        override fun findAll(): List<ScheduledJob> = rows.values.toList()

        override fun findById(id: UUID): ScheduledJob? = rows[id]

        override fun findDue(now: Instant): List<ScheduledJob> = emptyList()

        override fun findByType(type: ScheduledJobType): ScheduledJob? = rows.values.firstOrNull { it.type == type }

        override fun create(job: ScheduledJob): ScheduledJob {
            rows[job.id] = job
            return job
        }

        override fun update(job: ScheduledJob): ScheduledJob {
            rows[job.id] = job
            return job
        }

        override fun delete(id: UUID): Boolean = rows.remove(id) != null

        override fun updateScheduleState(
            id: UUID,
            nextRunAt: Instant,
            lastRunAt: Instant?,
            lastStatus: String?,
        ) {
            // not used in seeder tests
        }

        override fun recordLastRun(
            id: UUID,
            lastRunAt: Instant,
            lastStatus: String,
        ) {
            // not used in seeder tests
        }
    }

    private fun stubConfig(
        backup: BackupRestoreDto.BackupConfigDto = BackupRestoreDto.BackupConfigDto(),
        datev: ShopConfigurationDto.DatevXmlConfigResponseDto =
            ShopConfigurationDto.DatevXmlConfigResponseDto(
                shopId = 1L,
                kassenName = "Kasse",
                exportPath = "",
            ),
    ): ConfigurationService =
        object : ConfigurationService {
            override suspend fun getDatevXmlConfig(shopId: Long) = datev

            override suspend fun updateDatevXmlConfig(
                shopId: Long,
                request: ShopConfigurationDto.UpdateDatevXmlConfigRequestDto,
            ) = datev

            override suspend fun getBackupConfig(shopId: Long) = backup

            override suspend fun touchLastBackupAt(
                shopId: Long,
                at: String,
            ) = Unit

            override suspend fun recordBackupCopy(
                shopId: Long,
                at: String?,
                status: BackupCopyStatus,
                error: String?,
            ) = Unit
        }

    @Test
    fun `seeds BACKUP row from BACKUP_CONFIG with autoEnabled=true`() =
        runBlocking {
            val repo = FakeScheduledJobRepository()
            val cfg =
                stubConfig(
                    backup =
                        BackupRestoreDto.BackupConfigDto(
                            defaultBackupPath = "/tmp",
                            autoEnabled = true,
                            cadence = BackupRestoreDto.BackupCadence.DAILY,
                            retentionCount = 7,
                            runHour = 3,
                        ),
                )
            val seeder = BuiltInJobsSeeder(repo, cfg, NextRunCalculator())

            seeder.seedBackup()

            val backupRows = repo.findAll().filter { it.type == ScheduledJobType.BACKUP }
            assertEquals(1, backupRows.size, "Expected exactly one BACKUP row")
            val row = backupRows.single()
            assertTrue(row.enabled, "BACKUP row should be enabled")
            assertEquals(JobFrequency.DAILY, row.frequency)
            assertEquals("03:00", row.timeOfDay)
            assertNotNull(row.nextRunAt)
        }

    @Test
    fun `disables DATEV row when all per-type cadences are OFF`() =
        runBlocking {
            val repo = FakeScheduledJobRepository()
            val cfg =
                stubConfig(
                    datev =
                        ShopConfigurationDto.DatevXmlConfigResponseDto(
                            shopId = 1L,
                            kassenName = "Kasse",
                            exportPath = "",
                            enabled = true,
                            kassenbuchCadence = DatevAutoCadence.OFF,
                            apLedgerCadence = DatevAutoCadence.OFF,
                            buchungsstapelCadence = DatevAutoCadence.OFF,
                            runHour = 4,
                        ),
                )
            val seeder = BuiltInJobsSeeder(repo, cfg, NextRunCalculator())

            seeder.seedDatev()

            val rows = repo.findAll().filter { it.type == ScheduledJobType.DATEV_AUTO_EXPORT }
            assertEquals(1, rows.size)
            assertFalse(rows.single().enabled, "DATEV row should be disabled when all cadences OFF")
        }

    @Test
    fun `seeds UPDATE_CHECK enabled daily at 03-00, and a disabled row survives a reseed`() =
        runBlocking {
            val repo = FakeScheduledJobRepository()
            val seeder = BuiltInJobsSeeder(repo, stubConfig(), NextRunCalculator())

            seeder.seedUpdateCheck()

            val row = repo.findAll().single { it.type == ScheduledJobType.UPDATE_CHECK }
            assertTrue(row.enabled, "checking for a release is harmless; installing is the opt-in half")
            assertEquals(JobFrequency.DAILY, row.frequency)
            assertEquals("03:00", row.timeOfDay)
            assertNotNull(row.nextRunAt)

            repo.update(row.copy(enabled = false))
            seeder.seedUpdateCheck()

            assertFalse(
                repo.findAll().single { it.type == ScheduledJobType.UPDATE_CHECK }.enabled,
                "a reseed must not undo an operator switching the update check off",
            )
        }

    @Test
    fun `seeds ENTRY_LOG_RETENTION disabled on first insert`() =
        runBlocking {
            val repo = FakeScheduledJobRepository()
            val seeder = BuiltInJobsSeeder(repo, stubConfig(), NextRunCalculator())

            seeder.seedEntryLogRetention()

            val rows = repo.findAll().filter { it.type == ScheduledJobType.ENTRY_LOG_RETENTION }
            assertEquals(1, rows.size, "Expected exactly one ENTRY_LOG_RETENTION row")
            val row = rows.single()
            assertFalse(row.enabled, "ENTRY_LOG_RETENTION must be opt-in (disabled) on first insert")
            assertEquals(JobFrequency.DAILY, row.frequency)
            assertEquals("03:00", row.timeOfDay)
            assertNotNull(row.nextRunAt)
        }

    @Test
    fun `reseed preserves an operator-enabled ENTRY_LOG_RETENTION flag`() =
        runBlocking {
            val repo = FakeScheduledJobRepository()
            val seeder = BuiltInJobsSeeder(repo, stubConfig(), NextRunCalculator())
            seeder.seedEntryLogRetention()

            // Operator opts in via PUT /api/scheduled-jobs/{id}/enabled
            val row = repo.findAll().single { it.type == ScheduledJobType.ENTRY_LOG_RETENTION }
            repo.update(row.copy(enabled = true))

            // Server restarts → seedAll() upserts again
            seeder.seedEntryLogRetention()

            val rows = repo.findAll().filter { it.type == ScheduledJobType.ENTRY_LOG_RETENTION }
            assertEquals(1, rows.size, "Upsert must keep exactly one ENTRY_LOG_RETENTION row")
            assertTrue(rows.single().enabled, "Reseed must not reset the operator's enabled choice")
        }

    /**
     * The opposite judgement to entry-log retention, and deliberately so: that job deletes data and
     * is opt-in for legal reasons, while this one only materialises rows the operator already agreed
     * to by creating a contract. A contract that silently generated nothing would be worse than
     * useless.
     */
    @Test
    fun `seeds CONTRACT_INSTALMENTS enabled on first insert`() =
        runBlocking {
            val repo = FakeScheduledJobRepository()
            val seeder = BuiltInJobsSeeder(repo, stubConfig(), NextRunCalculator())

            seeder.seedContractInstalments()

            val row = repo.findAll().single { it.type == ScheduledJobType.CONTRACT_INSTALMENTS }
            assertTrue(row.enabled, "CONTRACT_INSTALMENTS must be on by default")
            assertEquals(JobFrequency.DAILY, row.frequency)
            assertEquals("02:00", row.timeOfDay)
            assertNotNull(row.nextRunAt)
        }

    @Test
    fun `reseed preserves an operator-disabled CONTRACT_INSTALMENTS flag`() =
        runBlocking {
            val repo = FakeScheduledJobRepository()
            val seeder = BuiltInJobsSeeder(repo, stubConfig(), NextRunCalculator())
            seeder.seedContractInstalments()

            val row = repo.findAll().single { it.type == ScheduledJobType.CONTRACT_INSTALMENTS }
            repo.update(row.copy(enabled = false))

            seeder.seedContractInstalments()

            val rows = repo.findAll().filter { it.type == ScheduledJobType.CONTRACT_INSTALMENTS }
            assertEquals(1, rows.size, "Upsert must keep exactly one CONTRACT_INSTALMENTS row")
            assertFalse(rows.single().enabled, "Reseed must not reset the operator's choice")
        }

    @Test
    fun `reseed still resyncs BACKUP enabled from config`() =
        runBlocking {
            val repo = FakeScheduledJobRepository()
            val cfg =
                stubConfig(
                    backup =
                        BackupRestoreDto.BackupConfigDto(
                            defaultBackupPath = "/tmp",
                            autoEnabled = false,
                            cadence = BackupRestoreDto.BackupCadence.DAILY,
                            retentionCount = 7,
                            runHour = 3,
                        ),
                )
            val seeder = BuiltInJobsSeeder(repo, cfg, NextRunCalculator())
            seeder.seedBackup()

            // Manual DB flip must NOT survive: backup's enabled flag derives from config
            val row = repo.findAll().single { it.type == ScheduledJobType.BACKUP }
            repo.update(row.copy(enabled = true))

            seeder.seedBackup()

            assertFalse(
                repo.findAll().single { it.type == ScheduledJobType.BACKUP }.enabled,
                "BACKUP enabled flag must keep following the backup configuration",
            )
        }

    @Test
    fun `enables DATEV row when at least one cadence is set and master enabled is true`() =
        runBlocking {
            val repo = FakeScheduledJobRepository()

            // Case A: only MONTHLY → frequency MONTHLY
            run {
                val cfg =
                    stubConfig(
                        datev =
                            ShopConfigurationDto.DatevXmlConfigResponseDto(
                                shopId = 1L,
                                kassenName = "Kasse",
                                exportPath = "",
                                enabled = true,
                                kassenbuchCadence = DatevAutoCadence.MONTHLY,
                                apLedgerCadence = DatevAutoCadence.OFF,
                                buchungsstapelCadence = DatevAutoCadence.OFF,
                                runHour = 4,
                            ),
                    )
                val seeder = BuiltInJobsSeeder(repo, cfg, NextRunCalculator())
                seeder.seedDatev()

                val row = repo.findAll().single { it.type == ScheduledJobType.DATEV_AUTO_EXPORT }
                assertTrue(row.enabled, "DATEV row should be enabled")
                assertEquals(JobFrequency.MONTHLY, row.frequency)
                assertEquals("04:00", row.timeOfDay)
                assertNotNull(row.nextRunAt)
            }

            // Case B: WEEKLY present → upserts (does not duplicate) and frequency becomes WEEKLY
            run {
                val cfg =
                    stubConfig(
                        datev =
                            ShopConfigurationDto.DatevXmlConfigResponseDto(
                                shopId = 1L,
                                kassenName = "Kasse",
                                exportPath = "",
                                enabled = true,
                                kassenbuchCadence = DatevAutoCadence.MONTHLY,
                                apLedgerCadence = DatevAutoCadence.WEEKLY,
                                buchungsstapelCadence = DatevAutoCadence.OFF,
                                runHour = 4,
                            ),
                    )
                val seeder = BuiltInJobsSeeder(repo, cfg, NextRunCalculator())
                seeder.seedDatev()

                val datevRows = repo.findAll().filter { it.type == ScheduledJobType.DATEV_AUTO_EXPORT }
                assertEquals(1, datevRows.size, "Upsert must keep exactly one DATEV row")
                assertEquals(JobFrequency.WEEKLY, datevRows.single().frequency)
                assertTrue(datevRows.single().enabled)
            }
        }
}
