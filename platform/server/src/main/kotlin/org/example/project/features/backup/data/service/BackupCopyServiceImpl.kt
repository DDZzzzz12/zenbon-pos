package org.example.project.features.backup.data.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.enums.backup.BackupCopyStatus
import org.example.project.core.domain.enums.backup.BackupCopyTargetKind
import org.example.project.core.utils.BackupCopyPathRules
import org.example.project.features.backup.domain.service.BackupCopyResult
import org.example.project.features.backup.domain.service.BackupCopyService
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * Copies an archive to a mounted device: guard, `<name>.part`, flush, size check, atomic rename,
 * then prunes the target by its own retention. A pulled stick leaves a `.part` file, which the next
 * run removes; the finished `name.zip` only ever appears complete.
 */
class BackupCopyServiceImpl(
    private val guard: MountPointGuard = MountPointGuard(),
) : BackupCopyService {
    private val logger = LoggerFactory.getLogger(BackupCopyServiceImpl::class.java)

    override suspend fun copy(
        archive: File,
        target: BackupRestoreDto.BackupCopyTargetDto,
        retention: Int,
    ): BackupCopyResult =
        withContext(Dispatchers.IO) {
            when (target.kind) {
                BackupCopyTargetKind.MOUNTED_PATH -> copyToMountedPath(archive, target.path, retention)
            }
        }

    private fun copyToMountedPath(
        archive: File,
        targetPath: String,
        retention: Int,
    ): BackupCopyResult {
        val localFolder =
            archive.absoluteFile.parentFile
                ?.path
                .orEmpty()
        if (BackupCopyPathRules.isSameOrInside(File(targetPath).absolutePath, localFolder)) {
            return failed("copy target $targetPath is inside the local backup folder $localFolder")
        }
        when (val verdict = guard.check(targetPath)) {
            is MountVerdict.NotMounted -> return BackupCopyResult(BackupCopyStatus.SKIPPED_NOT_MOUNTED, "device not mounted: ${verdict.reason}")
            is MountVerdict.Mounted -> logger.debug("copyToMountedPath: {} is on {} ({})", targetPath, verdict.mountPoint, verdict.fsType)
        }
        return try {
            val dir = File(targetPath)
            Files.createDirectories(dir.toPath())
            removeStalePartFiles(dir)
            val finished = writeAtomically(archive, dir)
            val pruned = pruneTarget(dir, keep = retention, newest = finished)
            BackupCopyResult(BackupCopyStatus.OK, "${finished.name} copied, pruned $pruned old", Instant.now(), finished.name)
        } catch (e: Exception) {
            logger.warn("copyToMountedPath: copy of {} to {} failed", archive.name, targetPath, e)
            failed(e.message ?: e::class.java.simpleName)
        }
    }

    private fun failed(reason: String) = BackupCopyResult(BackupCopyStatus.FAILED, reason)

    private fun removeStalePartFiles(dir: File) {
        dir.listFiles { _, n -> isOurs(n.removeSuffix(PART_SUFFIX)) && n.endsWith(PART_SUFFIX) }?.forEach { stale ->
            if (stale.delete()) logger.info("removeStalePartFiles: removed leftover {}", stale.name)
        }
    }

    private fun writeAtomically(
        archive: File,
        dir: File,
    ): File {
        val finished = File(dir, archive.name)
        val part = File(dir, archive.name + PART_SUFFIX)
        try {
            FileChannel.open(archive.toPath(), StandardOpenOption.READ).use { source ->
                FileChannel.open(part.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { sink ->
                    var position = 0L
                    val size = source.size()
                    while (position < size) position += source.transferTo(position, size - position, sink)
                    sink.force(true)
                }
            }
            if (part.length() != archive.length()) throw IOException("size mismatch after copy: ${part.length()} of ${archive.length()} bytes")
            Files.move(part.toPath(), finished.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        return finished
    }

    /** Keeps [keep] archives including [newest]; only `pos_backup_*.zip` is ever pruned, anything else on a shared stick stays. */
    private fun pruneTarget(
        dir: File,
        keep: Int,
        newest: File,
    ): Int {
        val others =
            dir
                .listFiles { f -> f.isFile && isOurs(f.name) && f.name != newest.name }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
        var pruned = 0
        others.drop((keep - 1).coerceAtLeast(0)).forEach { if (it.delete()) pruned++ }
        return pruned
    }

    private fun isOurs(name: String) = name.startsWith(ARCHIVE_PREFIX) && name.endsWith(".zip")

    private companion object {
        const val PART_SUFFIX = ".part"
        const val ARCHIVE_PREFIX = "pos_backup_"
    }
}
