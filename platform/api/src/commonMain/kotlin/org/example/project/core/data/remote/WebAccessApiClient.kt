package org.example.project.core.data.remote

import org.example.project.core.domain.dto.setup.ShopConfigurationDto

/**
 * Server-wide web settings: the API-docs switch and the default page for `/`.
 *
 * Kept apart from [ShopConfigurationApiClient] so its fakes need not grow two more members.
 * Throws `PosApiException`/`NetworkApiException` like every shared client.
 */
interface WebAccessApiClient {
    suspend fun getWebAccess(shopId: Long): ShopConfigurationDto.WebAccessResponseDto

    suspend fun updateWebAccess(
        shopId: Long,
        request: ShopConfigurationDto.UpdateWebAccessRequestDto,
    ): ShopConfigurationDto.WebAccessResponseDto
}
