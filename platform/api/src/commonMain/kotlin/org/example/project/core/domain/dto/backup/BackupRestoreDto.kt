/**
 * Container object for all Backup/Restore-related DTOs.
 *
 * Shared between client and server for database backup and restoration
 * operations. Used in the Data Export settings tab.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.dto.backup

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.setup.RestoredPathsDto
import org.example.project.core.domain.enums.backup.BackupCopyStatus
import org.example.project.core.domain.enums.backup.BackupCopyTargetKind
import org.example.project.core.utils.BackupCopyPathRules

/**
 * Container object for Backup/Restore DTOs.
 *
 * Provides data transfer objects for:
 * - Creating database backups
 * - Restoring database from backups
 * - Managing backup configuration
 */
object BackupRestoreDto {
    /**
     * Request DTO for creating a database backup.
     *
     * @property description Optional description for the backup
     */
    @Serializable
    data class CreateBackupRequestDto(
        val description: String? = null,
    )

    /**
     * Response DTO for backup metadata.
     *
     * Returned after successful backup creation with metadata about the backup.
     *
     * @property filename Generated filename for the backup
     * @property sizeBytes Size of the backup file in bytes
     * @property createdAt Timestamp when backup was created (ISO-8601)
     * @property description Optional description provided during creation
     */
    @Serializable
    data class BackupMetadataDto(
        val filename: String,
        val sizeBytes: Long,
        val createdAt: String,
        val description: String? = null,
    )

    /**
     * Cadence for automatic backup runs.
     */
    @Serializable
    enum class BackupCadence { DAILY, WEEKLY }

    /**
     * Response DTO for backup configuration settings.
     *
     * Contains settings related to backup operations.
     *
     * @property defaultBackupPath Default directory path for storing backups
     * @property lastBackupAt Timestamp of the last successful backup (ISO-8601 format, null if never backed up)
     * @property autoEnabled Whether automatic backups are enabled
     * @property cadence Cadence at which automatic backups run (daily or weekly)
     * @property retentionCount Number of backups to retain before pruning
     * @property runHour Hour of day (0..23) at which automatic backups run
     * @property maxUploadBytes Maximum allowed backup upload size in bytes (default 2 GB)
     * @property copyTo Optional second target each archive is copied to after the local backup
     * @property copyRetentionCount Number of archives kept on the copy target (1..365)
     * @property lastCopyAt When the last copy was verified (ISO-8601), null if never
     * @property lastCopyStatus Outcome of the last copy attempt
     * @property lastCopyError Short reason of the last failed attempt, cleared by the next success
     */
    @Serializable
    data class BackupConfigDto(
        val defaultBackupPath: String? = null,
        val lastBackupAt: String? = null,
        val autoEnabled: Boolean = false,
        val cadence: BackupCadence = BackupCadence.DAILY,
        val retentionCount: Int = 7,
        val runHour: Int = 3,
        val maxUploadBytes: Long = 2L * 1024 * 1024 * 1024,
        val copyTo: BackupCopyTargetDto? = null,
        val copyRetentionCount: Int = 7,
        val lastCopyAt: String? = null,
        val lastCopyStatus: BackupCopyStatus? = null,
        val lastCopyError: String? = null,
    )

    /** A typed second backup target (mounted device now; network or SSH later). */
    @Serializable
    data class BackupCopyTargetDto(
        val kind: BackupCopyTargetKind,
        val path: String,
    )

    /**
     * Request DTO for updating backup configuration.
     *
     * @property defaultBackupPath New default directory path for storing backups
     * @property autoEnabled Whether automatic backups are enabled
     * @property cadence Cadence at which automatic backups run (daily or weekly)
     * @property retentionCount Number of backups to retain before pruning
     * @property runHour Hour of day (0..23) at which automatic backups run
     * @property maxUploadBytes Maximum allowed backup upload size in bytes
     * @property copyTo Optional second target; null clears it (when [copyRetentionCount] is sent, see below)
     * @property copyRetentionCount Number of archives kept on the copy target (1..365). Null means "not sent":
     *   a client that predates the copy target omits both copy fields, and the server then keeps the stored
     *   target and retention instead of clearing them. To clear the target, send `copyTo` null together with a number.
     */
    @Serializable
    data class UpdateBackupConfigDto(
        val defaultBackupPath: String? = null,
        val autoEnabled: Boolean = false,
        val cadence: BackupCadence = BackupCadence.DAILY,
        val retentionCount: Int = 7,
        val runHour: Int = 3,
        val maxUploadBytes: Long = 2L * 1024 * 1024 * 1024,
        val copyTo: BackupCopyTargetDto? = null,
        val copyRetentionCount: Int? = null,
    ) {
        /**
         * Validates the update request.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(retentionCount in 1..365) { "retentionCount must be 1..365" }
            require(runHour in 0..23) { "runHour must be 0..23" }
            require(maxUploadBytes > 0) { "maxUploadBytes must be positive" }
            defaultBackupPath?.let {
                require(it.isNotBlank()) { "defaultBackupPath must not be blank if provided" }
            }
            copyRetentionCount?.let { require(it in 1..365) { "copyRetentionCount must be 1..365" } }
            copyTo?.let { target ->
                require(BackupCopyPathRules.isAbsolute(target.path)) { "copyTo.path must be an absolute path" }
                require(!BackupCopyPathRules.hasParentSegment(target.path)) { "copyTo.path must not contain '..'" }
                defaultBackupPath?.let {
                    require(!BackupCopyPathRules.isSameOrInside(target.path, it)) { "copyTo.path must not be inside the backup folder" }
                }
            }
        }
    }

    /**
     * Response DTO for restore operation result.
     *
     * @property success Whether the restore was successful
     * @property message Human-readable result message
     * @property restoredAt Timestamp when restore completed (ISO-8601)
     * @property restoredPathsNeedingReview Restored folder paths that point outside this
     *   installation (see `RestoredPathsDto`); non-empty means file writes to them are paused until
     *   an administrator reviews them
     */
    @Serializable
    data class RestoreResultDto(
        val success: Boolean,
        val message: String,
        val restoredAt: String,
        val restoredPathsNeedingReview: List<RestoredPathsDto.PathDto> = emptyList(),
    )

    /**
     * Request DTO for creating a backup and saving to server path.
     *
     * @property description Optional description for the backup
     * @property savePath Optional path on server to save the backup (uses default if null)
     */
    @Serializable
    data class CreateBackupToServerRequestDto(
        val description: String? = null,
        val savePath: String? = null,
    )

    /**
     * Response DTO for backup saved to server.
     *
     * @property success Whether the backup was saved successfully
     * @property filePath Full path where backup was saved
     * @property filename Generated filename
     * @property sizeBytes Size of the backup file in bytes
     * @property createdAt Timestamp when backup was created (ISO-8601)
     * @property copyStatus Outcome of the second copy; null when no copy target is configured
     * @property copyMessage Short result or reason of that copy
     */
    @Serializable
    data class BackupToServerResultDto(
        val success: Boolean,
        val filePath: String,
        val filename: String,
        val sizeBytes: Long,
        val createdAt: String,
        val copyStatus: BackupCopyStatus? = null,
        val copyMessage: String? = null,
    )

    /**
     * DTO for directory entry in directory browser.
     *
     * @property name Directory or file name
     * @property path Full path
     * @property isDirectory Whether this is a directory
     * @property isReadable Whether the directory/file is readable
     * @property isWritable Whether the directory/file is writable
     */
    @Serializable
    data class DirectoryEntryDto(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val isReadable: Boolean,
        val isWritable: Boolean,
    )

    /**
     * Response DTO for directory listing.
     *
     * @property currentPath The current directory path
     * @property parentPath Parent directory path (null if at root)
     * @property entries List of entries in the directory
     * @property canWrite Whether the current directory is writable
     */
    @Serializable
    data class DirectoryListingDto(
        val currentPath: String,
        val parentPath: String? = null,
        val entries: List<DirectoryEntryDto>,
        val canWrite: Boolean,
    )

    /**
     * DTO for listing backup files in a directory.
     *
     * @property filename Backup filename
     * @property filePath Full path to the backup file
     * @property sizeBytes File size in bytes
     * @property createdAt File creation/modification time
     */
    @Serializable
    data class BackupFileDto(
        val filename: String,
        val filePath: String,
        val sizeBytes: Long,
        val createdAt: String,
    )

    /**
     * Response DTO for listing available backups.
     *
     * @property backups List of backup files found
     * @property directory Directory that was scanned
     */
    @Serializable
    data class BackupListDto(
        val backups: List<BackupFileDto>,
        val directory: String,
    )

    /**
     * Request DTO for restoring from a server file.
     *
     * @property filePath Path to the backup file on the server
     */
    @Serializable
    data class RestoreFromServerRequestDto(
        val filePath: String,
    )
}
