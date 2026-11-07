package org.example.project.features.scheduler.data.service.executors

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.enums.backup.BackupCopyStatus
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.error.PosErrorCode
import org.example.project.features.backup.data.service.BackupCopyCoordinator
import org.example.project.features.backup.domain.service.BackupRestoreService
import org.example.project.features.scheduler.domain.service.JobPartiallyFailedException
import org.example.project.features.scheduler.domain.service.ScheduledJobExecutor
import org.example.project.features.setup.domain.service.ConfigurationService
import org.example.project.infrastructure.errors.PosException
import java.io.File
import java.time.Instant
import java.time.LocalDate

class BackupJobExecutor(
    private val backupService: BackupRestoreService,
    private val config: ConfigurationService,
    private val shopId: Long = 1L,
    private val copyCoordinator: BackupCopyCoordinator = BackupCopyCoordinator(config),
) : ScheduledJobExecutor {
    override val type = ScheduledJobType.BACKUP
    override val historyRetention = 30
    override val labelKey = "SCHEDULER_JOB_TYPE_BACKUP"
    override val restoredPathsWritten = setOf(RestoredPathSetting.BACKUP_PATH)

    override suspend fun execute(parametersJson: String): String {
        val cfg = config.getBackupConfig(shopId)
        val savePath =
            cfg.defaultBackupPath
                ?: throw PosException(
                    PosErrorCode.VALIDATION_FAILED,
                    HttpStatusCode.PreconditionFailed,
                    "BACKUP_CONFIG.defaultBackupPath is not set",
                )
        val retention = cfg.retentionCount

        val fileResult =
            try {
                backupService.createBackupToServer(
                    BackupRestoreDto.CreateBackupToServerRequestDto(
                        savePath = savePath,
                        description = "scheduled-${LocalDate.now()}",
                    ),
                )
            } catch (e: PosException) {
                throw e
            } catch (e: Exception) {
                throw PosException(
                    PosErrorCode.INTERNAL_ERROR,
                    HttpStatusCode.InternalServerError,
                    "Backup job execute failed: ${e.message}",
                    cause = e,
                )
            }

        val pruned = pruneOldBackups(savePath, retention)
        config.touchLastBackupAt(shopId, Instant.now().toString())

        val file = File(fileResult.filePath)
        val size = humanSize(fileResult.sizeBytes)
        val local = "${file.name}, $size, pruned $pruned old"
        val copy = copyCoordinator.copyAfterBackup(shopId, file) ?: return local
        if (copy.status == BackupCopyStatus.OK) return "$local; copy: OK → ${cfg.copyTo?.path}"
        // The local backup stands; the missing second copy is a visible partial failure.
        throw JobPartiallyFailedException(resultMessage = local, failedParts = listOf("copy: ${copy.message}"))
    }

    override fun validateParameters(parametersJson: String) { /* config-driven, no params */ }

    override fun parameterSchema(): JsonElement = JsonObject(emptyMap())

    private fun pruneOldBackups(
        dir: String,
        keep: Int,
    ): Int {
        // Only prune scheduled backups; never touch pre-restore safety backups.
        val zips =
            File(dir)
                .listFiles { _, n -> n.endsWith(".zip") && !n.startsWith("pre-restore_") }
                ?.toList()
                .orEmpty()
                .sortedByDescending { it.lastModified() }
        if (zips.size <= keep) return 0
        val toDelete = zips.drop(keep)
        var pruned = 0
        toDelete.forEach { if (it.delete()) pruned++ }
        return pruned
    }

    private fun humanSize(bytes: Long): String =
        when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
}
