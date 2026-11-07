package org.example.project.core.config

import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.utils.OsUtils
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Startup step that finishes a `--migrate-data-dir`: when `<base>/.data/previous-data-dir` exists,
 * the three stored absolute paths are rebased from the old base to the current one, then the marker
 * is deleted — only after every write succeeded, so a crash half-way simply retries on the next start.
 */
class DataDirectoryRebaseStep(
    private val readPath: suspend (RestoredPathSetting) -> String?,
    private val writePath: suspend (RestoredPathSetting, String) -> Unit,
    private val appDataDirectory: () -> String = { OsUtils.getAppDataDirectory() },
) {
    private val logger = LoggerFactory.getLogger(DataDirectoryRebaseStep::class.java)

    /** Returns how many stored paths were rewritten. */
    suspend fun runIfMarked(): Int {
        val newBase = appDataDirectory()
        val marker = File(newBase, DataDirectoryRebase.MARKER)
        if (!marker.isFile) return 0
        val oldBase = marker.readText().trim()
        if (oldBase.isEmpty()) {
            logger.warn("runIfMarked: marker {} is empty — ignored", marker)
            return 0
        }
        var written = 0
        for (setting in PATHS) {
            val current = readPath(setting)?.takeIf { it.isNotBlank() } ?: continue
            val rebased = DataDirectoryRebase.rebase(current, oldBase, newBase)
            if (rebased == current) continue
            writePath(setting, rebased)
            written++
            logger.info("runIfMarked: {} moved from '{}' to '{}'", setting, current, rebased)
        }
        if (!marker.delete()) logger.warn("runIfMarked: could not delete marker {}", marker)
        return written
    }

    private companion object {
        val PATHS =
            listOf(
                RestoredPathSetting.DOCUMENT_STORAGE_PATH,
                RestoredPathSetting.BACKUP_PATH,
                RestoredPathSetting.DATEV_EXPORT_PATH,
            )
    }
}
