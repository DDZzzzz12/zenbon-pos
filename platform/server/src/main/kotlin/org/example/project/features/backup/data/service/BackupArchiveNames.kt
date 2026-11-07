package org.example.project.features.backup.data.service

import java.io.File

/**
 * Names the archive a backup is written to. The timestamp has one-second resolution, so two backups started in
 * the same second used to get the same name and the second silently overwrote the first (locally and on the copy
 * target). The name is reserved atomically; a taken one gets `_2`, `_3`, ... before `.zip`, which keeps the
 * `pos_backup_*.zip` shape the retention pruning and the copy target look for.
 */
object BackupArchiveNames {
    private const val PREFIX = "pos_backup_"

    /** Creates and returns an empty, previously nonexistent archive file in [dir] for [timestamp]. */
    fun reserve(
        dir: File,
        timestamp: String,
    ): File {
        var candidate = File(dir, "$PREFIX$timestamp.zip")
        var attempt = 1
        while (!candidate.createNewFile()) {
            attempt++
            candidate = File(dir, "$PREFIX${timestamp}_$attempt.zip")
        }
        return candidate
    }
}
