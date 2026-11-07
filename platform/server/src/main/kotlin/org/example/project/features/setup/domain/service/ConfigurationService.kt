package org.example.project.features.setup.domain.service

import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.backup.BackupCopyStatus

/**
 * Service contract for reading and writing shop configuration values.
 *
 * Used by the scheduler executors and (eventually) the configuration routes
 * to share a single read/write path for backup and DATEV XML configuration.
 */
interface ConfigurationService {
    suspend fun getDatevXmlConfig(shopId: Long): ShopConfigurationDto.DatevXmlConfigResponseDto

    suspend fun updateDatevXmlConfig(
        shopId: Long,
        request: ShopConfigurationDto.UpdateDatevXmlConfigRequestDto,
    ): ShopConfigurationDto.DatevXmlConfigResponseDto

    suspend fun getBackupConfig(shopId: Long): BackupRestoreDto.BackupConfigDto

    /**
     * Persists the `lastBackupAt` field on `BACKUP_CONFIG` without touching
     * the other fields. Called by the scheduler after a successful backup run.
     */
    suspend fun touchLastBackupAt(
        shopId: Long,
        at: String,
    )

    /**
     * Records the outcome of a backup copy next to `lastBackupAt`. [at] is set only after a verified
     * copy (null leaves `lastCopyAt` untouched); [error] is cleared by passing null on success.
     */
    suspend fun recordBackupCopy(
        shopId: Long,
        at: String?,
        status: BackupCopyStatus,
        error: String?,
    )
}
