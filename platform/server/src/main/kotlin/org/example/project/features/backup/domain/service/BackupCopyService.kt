package org.example.project.features.backup.domain.service

import org.example.project.core.domain.dto.backup.BackupRestoreDto
import java.io.File

/** Copies a finished backup archive to the configured second target. Never throws for a copy problem. */
interface BackupCopyService {
    suspend fun copy(
        archive: File,
        target: BackupRestoreDto.BackupCopyTargetDto,
        retention: Int,
    ): BackupCopyResult
}
