package org.example.project.features.backup.domain.service

/**
 * Work a feature needs done once a backup restore has committed (e.g. re-aligning data the archive
 * carried from another installation). Implementations are collected by the restore service; a
 * failing hook is logged and does not fail the restore or stop later hooks.
 */
fun interface PostRestoreHook {
    suspend fun afterRestoreCommitted()
}
