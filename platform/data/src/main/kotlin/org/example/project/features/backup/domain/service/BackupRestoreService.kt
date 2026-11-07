/**
 * Service interface for database backup and restore operations.
 *
 * Provides contract for:
 * - Creating PostgreSQL database backups using JDBC COPY protocol
 * - Restoring database from backup ZIP archives via COPY FROM STDIN
 * - Managing backup configuration settings
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts to application/problem+json.
 * Migrated from Result<T, Error> in Stage B Batch 11.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.backup.domain.service

import org.example.project.core.domain.dto.backup.BackupRestoreDto
import java.io.File

/**
 * Service for database backup and restore operations.
 *
 * Manages database backup/restore using PostgreSQL's COPY protocol via JDBC.
 * Backups are stored as ZIP archives containing per-table CSV exports and metadata.
 * Schema is managed by Flyway migrations; only data is backed up and restored.
 */
interface BackupRestoreService {
    /**
     * Creates a database backup as a ZIP archive.
     *
     * Uses JDBC COPY TO STDOUT to export each table as CSV, packages them
     * into a ZIP archive with metadata (backup date, PG version, table list).
     *
     * @param request Optional backup request with description
     * @return ZIP archive as ByteArray
     */
    suspend fun createBackup(request: BackupRestoreDto.CreateBackupRequestDto? = null): ByteArray

    /**
     * Restores database from a backup ZIP archive.
     *
     * Uses JDBC COPY FROM STDIN to import CSV data for each table.
     * FK constraints are temporarily disabled during import, and sequences
     * are reset after restore.
     *
     * WARNING: This operation will replace all existing data in the database.
     *
     * @param backupData ZIP archive content as ByteArray
     * @return Restore result DTO
     */
    suspend fun restoreBackup(backupData: ByteArray): BackupRestoreDto.RestoreResultDto

    /**
     * Restores database from a backup ZIP file.
     *
     * Streams the file to the restore logic without loading entire file into memory.
     * Suitable for multi-GB backups.
     *
     * WARNING: This operation will replace all existing data in the database.
     *
     * @param backupFile The backup ZIP file to restore from
     * @return Restore result DTO
     */
    suspend fun restoreBackup(backupFile: File): BackupRestoreDto.RestoreResultDto

    /**
     * Gets the maximum allowed backup upload size in bytes.
     *
     * @return Max upload bytes from config, or default if not configured
     */
    suspend fun getMaxUploadBytes(): Long

    /**
     * Gets the current backup configuration.
     *
     * @param shopId Shop ID for configuration lookup
     * @return Backup configuration DTO
     */
    suspend fun getBackupConfig(shopId: Long): BackupRestoreDto.BackupConfigDto

    /**
     * Updates backup configuration settings.
     *
     * @param shopId Shop ID for configuration update
     * @param config Updated configuration values
     * @return Updated backup configuration DTO
     */
    suspend fun updateBackupConfig(
        shopId: Long,
        config: BackupRestoreDto.UpdateBackupConfigDto,
    ): BackupRestoreDto.BackupConfigDto

    /**
     * Creates a database backup and saves it as a ZIP file on the server.
     *
     * @param request Request with optional description and save path
     * @return Backup result with file path
     */
    suspend fun createBackupToServer(request: BackupRestoreDto.CreateBackupToServerRequestDto): BackupRestoreDto.BackupToServerResultDto

    /**
     * Restores database from a backup ZIP file on the server.
     *
     * @param filePath Path to the backup ZIP file on the server
     * @return Restore result DTO
     */
    suspend fun restoreFromServerFile(filePath: String): BackupRestoreDto.RestoreResultDto

    /**
     * Lists directories at the given path for directory browser.
     *
     * @param path Directory path to list (null for root/default)
     * @return Directory listing DTO
     */
    suspend fun listDirectory(path: String?): BackupRestoreDto.DirectoryListingDto

    /**
     * Lists backup files in the configured or specified directory.
     *
     * @param directory Directory to scan for backups (null uses default)
     * @return List of backup files
     */
    suspend fun listBackups(directory: String?): BackupRestoreDto.BackupListDto
}
