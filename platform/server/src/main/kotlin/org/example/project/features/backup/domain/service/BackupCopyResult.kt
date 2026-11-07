package org.example.project.features.backup.domain.service

import org.example.project.core.domain.enums.backup.BackupCopyStatus
import java.time.Instant

/** Outcome of one copy attempt. [copiedAt] is set only for [BackupCopyStatus.OK]. */
data class BackupCopyResult(
    val status: BackupCopyStatus,
    val message: String,
    val copiedAt: Instant? = null,
    val fileName: String? = null,
)
