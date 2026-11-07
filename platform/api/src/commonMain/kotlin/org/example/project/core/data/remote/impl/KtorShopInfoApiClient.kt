/**
 * Ktor-based implementation of ShopInfoApiClient.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import org.example.project.core.data.remote.ShopInfoApiClient
import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.core.domain.dto.setup.ShopInfoSyncDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorShopInfoApiClient(
    private val httpCall: HttpCall,
) : ShopInfoApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/shop-info"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getShopInfo(shopId: Long): ShopInfoDto.ShopInfoResponseDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$shopId")) }

    override suspend fun getShopInfoForSync(shopId: Long): ShopInfoSyncDto.ShopInfoSyncResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/sync")) { parameter("shopId", shopId) }
        }

    override suspend fun updateShopInfo(
        shopId: Long,
        request: ShopInfoDto.UpdateShopInfoRequestDto,
    ): ShopInfoDto.ShopInfoResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$shopId")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun uploadLogo(
        imageBytes: ByteArray,
        fileName: String,
    ): ShopInfoDto.LogoUploadResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/upload-logo")) {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "file",
                                imageBytes,
                                Headers.build {
                                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                                    append(
                                        HttpHeaders.ContentType,
                                        "image/${fileName.substringAfterLast('.', "jpeg").lowercase()}",
                                    )
                                },
                            )
                        },
                    ),
                )
            }
        }
}
