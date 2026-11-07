/**
 * Ktor implementation of ShopConfigurationApiClient.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.ShopConfigurationApiClient
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorShopConfigurationApiClient(
    private val httpCall: HttpCall,
) : ShopConfigurationApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/v1/config"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    private suspend inline fun <reified Req> putJson(
        path: String,
        body: Req,
    ): Unit =
        httpCall.httpClient.apiCall {
            put(url(path)) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    override suspend fun getBrowserStations(shopId: Long): ShopConfigurationDto.BrowserStationsResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$shopId/browser-stations")) }

    override suspend fun updateBrowserStations(
        shopId: Long,
        request: ShopConfigurationDto.UpdateBrowserStationsRequestDto,
    ): ShopConfigurationDto.BrowserStationsResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$shopId/browser-stations")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun listDirectories(path: String?): BackupRestoreDto.DirectoryListingDto =
        httpCall.httpClient.apiCall {
            get(url("api/v1/backup/directories")) {
                path?.let { parameter("path", it) }
            }
        }
}
