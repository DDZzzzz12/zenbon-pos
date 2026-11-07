/**
 * Ktor-based implementation of ProductExtrasApiClient.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
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
import org.example.project.core.data.remote.ProductExtrasApiClient
import org.example.project.core.domain.dto.product.ProductExtrasDto
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorProductExtrasApiClient(
    private val httpCall: HttpCall,
) : ProductExtrasApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/product-extras"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getAllProductExtras(
        isActive: Boolean?,
        limit: Int?,
        offset: Int?,
    ): List<ProductExtrasDto.ProductExtrasResponseDto> =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                isActive?.let { parameter("isActive", it) }
                limit?.let { parameter("limit", it) }
                offset?.let { parameter("offset", it) }
            }
        }

    override suspend fun getProductExtraById(id: Long): ProductExtrasDto.ProductExtrasResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$id")) }

    override suspend fun createProductExtra(request: ProductExtrasDto.CreateProductExtrasRequestDto): ProductExtrasDto.ProductExtrasResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateProductExtra(
        id: Long,
        request: ProductExtrasDto.UpdateProductExtrasRequestDto,
    ): ProductExtrasDto.ProductExtrasResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deleteProductExtra(id: Long) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$BASE_ENDPOINT/$id")) }
    }

    override suspend fun syncProductExtras(lastSyncTime: String): List<ProductExtrasDto.ProductExtrasResponseDto> =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/sync/$lastSyncTime")) }

    override suspend fun importExtrasCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean,
    ): InitialSetupDto.CsvImportResultDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/import-csv/$overwrite")) {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "file",
                                csvContent,
                                Headers.build {
                                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                                    append(HttpHeaders.ContentType, "text/csv")
                                },
                            )
                        },
                    ),
                )
            }
        }
}
