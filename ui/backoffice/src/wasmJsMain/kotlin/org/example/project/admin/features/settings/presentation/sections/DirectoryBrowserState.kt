package org.example.project.admin.features.settings.presentation.sections

import org.example.project.core.domain.dto.backup.BackupRestoreDto

/**
 * The server-side directory browser, non-null exactly while its dialog is open.
 *
 * A browser has no native directory picker, but none is needed: the path being chosen is on the
 * server, and `ShopConfigurationApiClient.listDirectories` already exposes the listing
 * (`GET /api/v1/backup/directories`).
 *
 * [entries] holds directories only — files are filtered out before they reach here, since a storage
 * root can only be a folder.
 */
data class DirectoryBrowserState(
    val currentPath: String = "",
    val parentPath: String? = null,
    val entries: List<BackupRestoreDto.DirectoryEntryDto> = emptyList(),
    val canWrite: Boolean = false,
    val isLoading: Boolean = false,
)
