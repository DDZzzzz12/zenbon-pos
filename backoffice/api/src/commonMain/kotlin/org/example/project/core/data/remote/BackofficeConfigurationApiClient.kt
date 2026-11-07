/**
 * API client interface for the backoffice-owned shop configuration: the work-time break rules.
 *
 * Split out of `ShopConfigurationApiClient` (Stage B2-5, Task C1); every method keeps its endpoint.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.setup.ShopConfigurationDto

interface BackofficeConfigurationApiClient {
    suspend fun getBreakRulesConfig(shopId: Long): ShopConfigurationDto.BreakRulesConfigResponseDto

    suspend fun updateBreakRulesConfig(
        shopId: Long,
        request: ShopConfigurationDto.BreakRulesConfig,
    ): ShopConfigurationDto.BreakRulesConfigResponseDto
}
