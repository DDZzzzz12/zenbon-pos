package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.WebAccessApiClient
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

/** Ktor implementation of [WebAccessApiClient] over `GET/PUT /api/v1/config/{shopId}/web-access`. */
class KtorWebAccessApiClient(
    private val httpCall: HttpCall,
) : WebAccessApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/v1/config"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getWebAccess(shopId: Long): ShopConfigurationDto.WebAccessResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$shopId/web-access")) }

    override suspend fun updateWebAccess(
        shopId: Long,
        request: ShopConfigurationDto.UpdateWebAccessRequestDto,
    ): ShopConfigurationDto.WebAccessResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$shopId/web-access")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
}
