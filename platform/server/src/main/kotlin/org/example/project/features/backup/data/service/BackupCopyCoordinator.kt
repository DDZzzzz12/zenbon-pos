package org.example.project.features.backup.data.service

import kotlinx.coroutines.CancellationException
import org.example.project.core.domain.enums.backup.BackupCopyStatus
import org.example.project.features.backup.domain.service.BackupCopyResult
import org.example.project.features.backup.domain.service.BackupCopyService
import org.example.project.features.setup.domain.service.ConfigurationService
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Runs the configured second copy after a local backup succeeded, records the outcome next to
 * `lastBackupAt` and writes one audit line per attempt (file name, size and target kind — never
 * the device's contents). A copy problem is returned, never thrown: the local backup stands.
 */
class BackupCopyCoordinator(
    private val config: ConfigurationService,
    private val copyService: BackupCopyService = BackupCopyServiceImpl(),
    private val copyTargetAwaitsReview: suspend () -> Boolean = { false },
) {
    private val logger = LoggerFactory.getLogger(BackupCopyCoordinator::class.java)

    /**
     * Null when no copy target is configured (nothing is recorded in that case). A copy target
     * restored from another installation is not written to until an administrator has reviewed it;
     * that pauses the copy only, never the local backup.
     */
    suspend fun copyAfterBackup(
        shopId: Long,
        archive: File,
    ): BackupCopyResult? =
        try {
            runCopy(shopId, archive)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("copyAfterBackup: copy step of {} failed", archive.name, e)
            BackupCopyResult(BackupCopyStatus.FAILED, e.message ?: e::class.java.simpleName)
        }

    private suspend fun runCopy(
        shopId: Long,
        archive: File,
    ): BackupCopyResult? {
        val cfg = config.getBackupConfig(shopId)
        val target = cfg.copyTo ?: return null
        val result =
            if (copyTargetAwaitsReview()) {
                BackupCopyResult(BackupCopyStatus.FAILED, AWAITS_REVIEW_MESSAGE)
            } else {
                copyService.copy(archive, target, cfg.copyRetentionCount)
            }
        audit(archive, target.kind.name, result)
        config.recordBackupCopy(
            shopId = shopId,
            at = result.copiedAt?.toString(),
            status = result.status,
            error = result.message.takeIf { result.status != BackupCopyStatus.OK },
        )
        return result
    }

    private fun audit(
        archive: File,
        targetKind: String,
        result: BackupCopyResult,
    ) {
        AUDIT.info("BACKUP_COPY file={} size={} targetKind={} status={}", archive.name, archive.length(), targetKind, result.status)
        logger.debug("audit: copy of {} finished: {}", archive.name, result.message)
    }

    private companion object {
        const val AWAITS_REVIEW_MESSAGE =
            "the copy target was restored from another installation and awaits review (/api/v1/backup/restored-paths)"
        val AUDIT = LoggerFactory.getLogger("AuditLog.BackupCopy")
    }
}
