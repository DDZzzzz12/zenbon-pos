package org.example.project.core.domain.enums.backup

import kotlinx.serialization.Serializable

/** Where a backup's second copy goes. Network and SSH targets can be added later without a wire change. */
@Serializable
enum class BackupCopyTargetKind {
    /** A folder on a mounted device, e.g. `/media/pos/ZEN_BACKUP`. */
    MOUNTED_PATH,
}
