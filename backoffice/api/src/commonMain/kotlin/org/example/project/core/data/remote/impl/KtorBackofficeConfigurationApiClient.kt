/**
 * Ktor implementation of BackofficeConfigurationApiClient.
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
import org.example.project.core.data.remote.BackofficeConfigurationApiClient
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorBackofficeConfigurationApiClient(
    private val httpCall: HttpCall,
) : BackofficeConfigurationApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/v1/config"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getBreakRulesConfig(shopId: Long): ShopConfigurationDto.BreakRulesConfigResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$shopId/break-rules")) }

    override suspend fun updateBreakRulesConfig(
        shopId: Long,
        request: ShopConfigurationDto.BreakRulesConfig,
    ): ShopConfigurationDto.BreakRulesConfigResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$shopId/break-rules")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
}
