package org.example.project.admin.features.settings

import org.example.project.core.data.remote.WebAccessApiClient
import org.example.project.core.domain.dto.setup.ShopConfigurationDto

/**
 * Holds the web-access settings in memory and applies an update the way the server does. [updateError],
 * when set, is thrown by the next update so a test can prove a failed save keeps the saved state.
 */
class FakeWebAccessApiClient : WebAccessApiClient {
    var stored: ShopConfigurationDto.WebAccessResponseDto =
        ShopConfigurationDto.WebAccessResponseDto(
            shopId = 1L,
            apiDocsEnabled = false,
            apiDocsSetting = false,
            apiDocsLockedByServerConfig = false,
            startPageDefault = "none",
            availableStartPageTargets = listOf("none", "admin"),
        )
    val updates: MutableList<ShopConfigurationDto.UpdateWebAccessRequestDto> = mutableListOf()
    var updateError: Throwable? = null

    override suspend fun getWebAccess(shopId: Long): ShopConfigurationDto.WebAccessResponseDto = stored

    override suspend fun updateWebAccess(
        shopId: Long,
        request: ShopConfigurationDto.UpdateWebAccessRequestDto,
    ): ShopConfigurationDto.WebAccessResponseDto {
        updateError?.let { throw it }
        updates += request
        stored =
            stored.copy(
                apiDocsSetting = request.apiDocsEnabled,
                apiDocsEnabled = if (stored.apiDocsLockedByServerConfig) stored.apiDocsEnabled else request.apiDocsEnabled,
                startPageDefault = request.startPageDefault,
            )
        return stored
    }
}
