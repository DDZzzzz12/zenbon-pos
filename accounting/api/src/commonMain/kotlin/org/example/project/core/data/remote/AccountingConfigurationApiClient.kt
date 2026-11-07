/**
 * API client interface for the accounting-owned shop configuration: the DATEV export settings and the document storage settings.
 *
 * Split out of `ShopConfigurationApiClient` (Stage B2-5, Task C1); every method keeps its endpoint.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto

interface AccountingConfigurationApiClient {
    suspend fun getDatevXmlConfig(shopId: Long): ShopConfigurationDto.DatevXmlConfigResponseDto

    suspend fun updateDatevXmlConfig(
        shopId: Long,
        request: ShopConfigurationDto.UpdateDatevXmlConfigRequestDto,
    ): ShopConfigurationDto.DatevXmlConfigResponseDto

    suspend fun getDatevAccountMapping(shopId: Long): DatevAccountMappingDto.MappingResponseDto

    suspend fun updateDatevAccountMapping(
        shopId: Long,
        request: DatevAccountMappingDto.MappingDto,
    ): DatevAccountMappingDto.MappingResponseDto

    suspend fun getDocumentStorageConfig(shopId: Long): ShopConfigurationDto.DocumentStorageConfigResponseDto

    suspend fun updateDocumentStorageConfig(
        shopId: Long,
        request: ShopConfigurationDto.UpdateDocumentStorageConfigRequestDto,
    ): ShopConfigurationDto.DocumentStorageConfigResponseDto
}
