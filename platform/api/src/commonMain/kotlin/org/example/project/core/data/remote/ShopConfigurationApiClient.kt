/**
 * API client interface for shop configuration operations.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto

interface ShopConfigurationApiClient {
    suspend fun getBrowserStations(shopId: Long): ShopConfigurationDto.BrowserStationsResponseDto

    suspend fun updateBrowserStations(
        shopId: Long,
        request: ShopConfigurationDto.UpdateBrowserStationsRequestDto,
    ): ShopConfigurationDto.BrowserStationsResponseDto

    suspend fun listDirectories(path: String? = null): BackupRestoreDto.DirectoryListingDto
}
