/**
 * API client interface for database backup and restore operations.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.setup.RestoredPathsDto

interface BackupApiClient {
    suspend fun createBackup(request: BackupRestoreDto.CreateBackupRequestDto? = null): ByteArray

    suspend fun restoreBackup(
        backupData: ByteArray,
        filename: String,
    ): BackupRestoreDto.RestoreResultDto

    suspend fun getBackupConfig(): BackupRestoreDto.BackupConfigDto

    suspend fun updateBackupConfig(config: BackupRestoreDto.UpdateBackupConfigDto): BackupRestoreDto.BackupConfigDto

    suspend fun createBackupToServer(request: BackupRestoreDto.CreateBackupToServerRequestDto): BackupRestoreDto.BackupToServerResultDto

    suspend fun restoreFromServerFile(filePath: String): BackupRestoreDto.RestoreResultDto

    suspend fun listDirectories(path: String? = null): BackupRestoreDto.DirectoryListingDto

    suspend fun listBackups(directory: String? = null): BackupRestoreDto.BackupListDto

    /** Restored folder paths of another installation awaiting review (`GET restored-paths`). */
    suspend fun getRestoredPaths(): RestoredPathsDto.ReviewDto

    /** Keeps or resets restored paths; the pause lifts once none is left (`POST restored-paths`). */
    suspend fun resolveRestoredPaths(request: RestoredPathsDto.ResolveRequestDto): RestoredPathsDto.ReviewDto
}
