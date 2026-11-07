package org.example.project.core.domain.enums.backup

import kotlinx.serialization.Serializable

/** Outcome of the last attempt to copy a backup archive to the configured second target. */
@Serializable
enum class BackupCopyStatus {
    OK,
    FAILED,
    SKIPPED_NO_TARGET,
    SKIPPED_NOT_MOUNTED,
}
