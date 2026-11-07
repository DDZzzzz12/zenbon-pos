package org.example.project.core.data.remote

import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.core.domain.dto.setup.ShopInfoSyncDto

/**
 * API client interface for shop information operations.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
interface ShopInfoApiClient {
    suspend fun getShopInfo(shopId: Long = 1): ShopInfoDto.ShopInfoResponseDto

    suspend fun getShopInfoForSync(shopId: Long = 1): ShopInfoSyncDto.ShopInfoSyncResponseDto

    suspend fun updateShopInfo(
        shopId: Long = 1,
        request: ShopInfoDto.UpdateShopInfoRequestDto,
    ): ShopInfoDto.ShopInfoResponseDto

    suspend fun uploadLogo(
        imageBytes: ByteArray,
        fileName: String,
    ): ShopInfoDto.LogoUploadResponseDto
}
