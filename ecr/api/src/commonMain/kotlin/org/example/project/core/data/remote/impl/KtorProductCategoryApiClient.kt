/**
 * Ktor-based implementation of ProductCategoryApiClient.
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
import org.example.project.core.data.remote.ProductCategoryApiClient
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorProductCategoryApiClient(
    private val httpCall: HttpCall,
) : ProductCategoryApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/product-categories"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getAllProductCategories(
        isActive: Boolean?,
        limit: Int?,
        offset: Int?,
    ): List<ProductCategoryDto.ProductCategoryResponseDto> =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                isActive?.let { parameter("isActive", it) }
                limit?.let { parameter("limit", it) }
                offset?.let { parameter("offset", it) }
            }
        }

    override suspend fun getProductCategoryById(id: Long): ProductCategoryDto.ProductCategoryResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$id")) }

    override suspend fun createProductCategory(request: ProductCategoryDto.CreateProductCategoryRequestDto): ProductCategoryDto.ProductCategoryResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateProductCategory(
        id: Long,
        request: ProductCategoryDto.UpdateProductCategoryRequestDto,
    ): ProductCategoryDto.ProductCategoryResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deleteProductCategory(id: Long) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$BASE_ENDPOINT/$id")) }
    }

    override suspend fun syncProductCategories(lastSyncTime: String): List<ProductCategoryDto.ProductCategoryResponseDto> =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/sync/$lastSyncTime")) }

    override suspend fun updateCategoryPositions(updates: List<ProductCategoryDto.UpdateProductCategoryPositionRequestDto>) {
        httpCall.httpClient.apiCall<Unit> {
            put(url("$BASE_ENDPOINT/positions")) {
                contentType(ContentType.Application.Json)
                setBody(updates)
            }
        }
    }

    override suspend fun importCategoriesCsv(
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
