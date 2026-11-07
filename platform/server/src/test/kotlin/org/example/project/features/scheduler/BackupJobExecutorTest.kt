package org.example.project.features.scheduler

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.backup.BackupCopyStatus
import org.example.project.core.domain.enums.backup.BackupCopyTargetKind
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.features.backup.data.service.BackupCopyCoordinator
import org.example.project.features.backup.domain.service.BackupCopyResult
import org.example.project.features.backup.domain.service.BackupCopyService
import org.example.project.features.backup.domain.service.BackupRestoreService
import org.example.project.features.scheduler.data.service.executors.BackupJobExecutor
import org.example.project.features.scheduler.domain.service.JobPartiallyFailedException
import org.example.project.features.setup.domain.service.ConfigurationService
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertNotNull as assertNotNullKt

class BackupJobExecutorTest {
    private class FakeBackupService(
        val writeTo: Path,
    ) : BackupRestoreService {
        override suspend fun createBackup(request: BackupRestoreDto.CreateBackupRequestDto?): ByteArray = error("not used")

        override suspend fun restoreBackup(backupData: ByteArray): BackupRestoreDto.RestoreResultDto = error("not used")

        override suspend fun restoreBackup(backupFile: File): BackupRestoreDto.RestoreResultDto = error("not used")

        override suspend fun getMaxUploadBytes(): Long = 2L * 1024 * 1024 * 1024

        override suspend fun getBackupConfig(shopId: Long): BackupRestoreDto.BackupConfigDto = error("not used — executor reads via ConfigurationService")

        override suspend fun updateBackupConfig(
            shopId: Long,
            config: BackupRestoreDto.UpdateBackupConfigDto,
        ): BackupRestoreDto.BackupConfigDto = error("not used")

        override suspend fun createBackupToServer(request: BackupRestoreDto.CreateBackupToServerRequestDto): BackupRestoreDto.BackupToServerResultDto {
            val savePath = request.savePath ?: writeTo.toString()
            val target = File(savePath, "backup-${System.nanoTime()}.zip")
            target.writeBytes(ByteArray(16))
            return BackupRestoreDto.BackupToServerResultDto(
                success = true,
                filePath = target.absolutePath,
                filename = target.name,
                sizeBytes = 16L,
                createdAt = System.currentTimeMillis().toString(),
            )
        }

        override suspend fun restoreFromServerFile(filePath: String): BackupRestoreDto.RestoreResultDto = error("not used")

        override suspend fun listDirectory(path: String?): BackupRestoreDto.DirectoryListingDto = error("not used")

        override suspend fun listBackups(directory: String?): BackupRestoreDto.BackupListDto = error("not used")
    }

    private class StubConfigService(
        private val backup: BackupRestoreDto.BackupConfigDto,
    ) : ConfigurationService {
        var touched: String? = null
        val copyRecords = mutableListOf<Triple<String?, BackupCopyStatus, String?>>()

        override suspend fun getDatevXmlConfig(shopId: Long): ShopConfigurationDto.DatevXmlConfigResponseDto = error("not used")

        override suspend fun updateDatevXmlConfig(
            shopId: Long,
            request: ShopConfigurationDto.UpdateDatevXmlConfigRequestDto,
        ): ShopConfigurationDto.DatevXmlConfigResponseDto = error("not used")

        override suspend fun getBackupConfig(shopId: Long): BackupRestoreDto.BackupConfigDto = backup

        override suspend fun touchLastBackupAt(
            shopId: Long,
            at: String,
        ) {
            touched = at
        }

        override suspend fun recordBackupCopy(
            shopId: Long,
            at: String?,
            status: BackupCopyStatus,
            error: String?,
        ) {
            copyRecords += Triple(at, status, error)
        }
    }

    @Test fun `executes and prunes old backups beyond retentionCount`(
        @TempDir tmp: Path,
    ) = runBlocking {
        // Seed 5 old backups.
        repeat(5) { i ->
            val f = File(tmp.toFile(), "old-$i.zip")
            f.writeBytes(ByteArray(8))
            f.setLastModified(1_000_000L + i * 1000L)
        }
        val configStub =
            StubConfigService(
                BackupRestoreDto.BackupConfigDto(
                    defaultBackupPath = tmp.toString(),
                    retentionCount = 3,
                ),
            )
        val exec = BackupJobExecutor(FakeBackupService(tmp), configStub)

        val result = exec.execute("{}")

        assertNotNull(result)
        val zips = tmp.toFile().listFiles { _, n -> n.endsWith(".zip") }!!.toList()
        assertEquals(3, zips.size, "Expected 3 zips retained, got: ${zips.map { it.name }}")
        assertNotNullKt(configStub.touched, "lastBackupAt should be persisted after a successful run")
    }

    @Test fun `throws when defaultBackupPath is not configured`() {
        val configStub =
            StubConfigService(
                BackupRestoreDto.BackupConfigDto(defaultBackupPath = null),
            )
        val exec =
            BackupJobExecutor(
                FakeBackupService(
                    java.nio.file.Paths
                        .get(System.getProperty("java.io.tmpdir")),
                ),
                configStub,
            )
        assertThrows(PosException::class.java) {
            kotlinx.coroutines.runBlocking { exec.execute("{}") }
        }
    }

    @Test fun `validateParameters is a no-op for any input`() {
        val configStub = StubConfigService(BackupRestoreDto.BackupConfigDto())
        val exec =
            BackupJobExecutor(
                FakeBackupService(
                    java.nio.file.Paths
                        .get(System.getProperty("java.io.tmpdir")),
                ),
                configStub,
            )
        // Must not throw — config-driven, no parameters expected.
        exec.validateParameters("")
        exec.validateParameters("{}")
        exec.validateParameters("""{"retentionCount":-99}""")
    }

    private class FakeCopyService(
        val result: BackupCopyResult,
    ) : BackupCopyService {
        var calls = 0

        override suspend fun copy(
            archive: File,
            target: BackupRestoreDto.BackupCopyTargetDto,
            retention: Int,
        ): BackupCopyResult {
            calls++
            return result
        }
    }

    private val stick = BackupRestoreDto.BackupCopyTargetDto(BackupCopyTargetKind.MOUNTED_PATH, "/media/pos/ZEN_BACKUP")

    private fun copyFixture(
        tmp: Path,
        result: BackupCopyResult,
        target: BackupRestoreDto.BackupCopyTargetDto? = stick,
    ): Triple<BackupJobExecutor, StubConfigService, FakeCopyService> {
        val configStub = StubConfigService(BackupRestoreDto.BackupConfigDto(defaultBackupPath = tmp.toString(), copyTo = target))
        val copier = FakeCopyService(result)
        val exec = BackupJobExecutor(FakeBackupService(tmp), configStub, copyCoordinator = BackupCopyCoordinator(configStub, copier))
        return Triple(exec, configStub, copier)
    }

    @Test fun `no copy target keeps the result text and records nothing`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val (exec, cfg, copier) = copyFixture(tmp, BackupCopyResult(BackupCopyStatus.OK, "x"), target = null)
        val result = exec.execute("{}")
        assertEquals(true, Regex("""backup-\d+\.zip, 16 B, pruned 0 old""").matches(result), result)
        assertEquals(0, copier.calls)
        assertEquals(emptyList<Any>(), cfg.copyRecords)
    }

    @Test fun `an OK copy is appended to the result and recorded`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val at = Instant.parse("2026-11-01T03:00:05Z")
        val (exec, cfg, _) = copyFixture(tmp, BackupCopyResult(BackupCopyStatus.OK, "copied", at))
        val result = exec.execute("{}")
        assertEquals(true, result.endsWith("; copy: OK → /media/pos/ZEN_BACKUP"), result)
        assertEquals(listOf(Triple<String?, BackupCopyStatus, String?>(at.toString(), BackupCopyStatus.OK, null)), cfg.copyRecords)
    }

    @Test fun `a failed or skipped copy is a partial failure and the local backup stands`(
        @TempDir tmp: Path,
    ) {
        listOf(BackupCopyStatus.FAILED to "disk full", BackupCopyStatus.SKIPPED_NOT_MOUNTED to "device not mounted").forEach { (status, message) ->
            val (exec, cfg, _) = copyFixture(tmp, BackupCopyResult(status, message))
            val e = assertThrows(JobPartiallyFailedException::class.java) { runBlocking { exec.execute("{}") } }
            assertEquals(listOf("copy: $message"), e.failedParts)
            assertEquals(true, e.resultMessage.startsWith("backup-"), e.resultMessage)
            assertEquals(true, tmp.toFile().listFiles { _, n -> n.endsWith(".zip") }!!.isNotEmpty())
            assertNotNullKt(cfg.touched, "lastBackupAt is set although the copy failed")
            assertEquals(listOf(Triple<String?, BackupCopyStatus, String?>(null, status, message)), cfg.copyRecords)
        }
    }

    @Test fun `a copy target awaiting review is not written to, the local backup stands`(
        @TempDir tmp: Path,
    ) {
        val configStub = StubConfigService(BackupRestoreDto.BackupConfigDto(defaultBackupPath = tmp.toString(), copyTo = stick))
        val copier = FakeCopyService(BackupCopyResult(BackupCopyStatus.OK, "x"))
        val exec =
            BackupJobExecutor(
                FakeBackupService(tmp),
                configStub,
                copyCoordinator = BackupCopyCoordinator(configStub, copier, copyTargetAwaitsReview = { true }),
            )
        val e = assertThrows(JobPartiallyFailedException::class.java) { runBlocking { exec.execute("{}") } }
        assertEquals(0, copier.calls)
        assertEquals(true, e.failedParts.single().contains("awaits review"), e.failedParts.toString())
        assertNotNullKt(configStub.touched, "lastBackupAt is set although the copy was held back")
    }

    @Test fun `a throwing copy step becomes a failed copy, not a failed job`(
        @TempDir tmp: Path,
    ) {
        val configStub = StubConfigService(BackupRestoreDto.BackupConfigDto(defaultBackupPath = tmp.toString(), copyTo = stick))
        val boom =
            object : BackupCopyService {
                override suspend fun copy(
                    archive: File,
                    target: BackupRestoreDto.BackupCopyTargetDto,
                    retention: Int,
                ): BackupCopyResult = error("device vanished")
            }
        val exec = BackupJobExecutor(FakeBackupService(tmp), configStub, copyCoordinator = BackupCopyCoordinator(configStub, boom))
        val e = assertThrows(JobPartiallyFailedException::class.java) { runBlocking { exec.execute("{}") } }
        assertEquals(listOf("copy: device vanished"), e.failedParts)
    }

    @Test fun `the job pauses only on the local backup path, the copy path pauses the copy alone`() {
        val exec =
            BackupJobExecutor(
                FakeBackupService(
                    java.nio.file.Paths
                        .get("/tmp"),
                ),
                StubConfigService(BackupRestoreDto.BackupConfigDto()),
            )
        assertEquals(setOf(RestoredPathSetting.BACKUP_PATH), exec.restoredPathsWritten)
    }
}
