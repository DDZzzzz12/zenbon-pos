/**
 * Ktor implementation of AccountingConfigurationApiClient.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.AccountingConfigurationApiClient
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorAccountingConfigurationApiClient(
    private val httpCall: HttpCall,
) : AccountingConfigurationApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/v1/config"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getDatevXmlConfig(shopId: Long): ShopConfigurationDto.DatevXmlConfigResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$shopId/datev-xml")) }

    override suspend fun updateDatevXmlConfig(
        shopId: Long,
        request: ShopConfigurationDto.UpdateDatevXmlConfigRequestDto,
    ): ShopConfigurationDto.DatevXmlConfigResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$shopId/datev-xml")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getDatevAccountMapping(shopId: Long): DatevAccountMappingDto.MappingResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$shopId/datev-account-mapping")) }

    override suspend fun updateDatevAccountMapping(
        shopId: Long,
        request: DatevAccountMappingDto.MappingDto,
    ): DatevAccountMappingDto.MappingResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$shopId/datev-account-mapping")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getDocumentStorageConfig(shopId: Long): ShopConfigurationDto.DocumentStorageConfigResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$shopId/document-storage")) }

    override suspend fun updateDocumentStorageConfig(
        shopId: Long,
        request: ShopConfigurationDto.UpdateDocumentStorageConfigRequestDto,
    ): ShopConfigurationDto.DocumentStorageConfigResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$shopId/document-storage")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
}
