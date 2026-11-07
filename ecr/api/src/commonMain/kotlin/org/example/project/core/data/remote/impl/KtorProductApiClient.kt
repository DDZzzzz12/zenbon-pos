/**
 * Ktor-based implementation of ProductApiClient.
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
import org.example.project.core.data.remote.ProductApiClient
import org.example.project.core.domain.dto.product.FavouriteProductDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorProductApiClient(
    private val httpCall: HttpCall,
) : ProductApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/products"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getAllProducts(
        isActive: Boolean?,
        categoryId: Long?,
        limit: Int?,
        offset: Int?,
    ): List<ProductDto.ProductResponseDto> =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                isActive?.let { parameter("isActive", it) }
                categoryId?.let { parameter("categoryId", it) }
                limit?.let { parameter("limit", it) }
                offset?.let { parameter("offset", it) }
            }
        }

    override suspend fun getProductById(id: Long): ProductDto.ProductResponseDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$id")) }

    override suspend fun getProductByNumber(productNumber: String): ProductDto.ProductResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/by-number/$productNumber")) }

    override suspend fun getProductsByCategory(categoryId: Long): List<ProductDto.ProductResponseDto> =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/by-category/$categoryId")) }

    override suspend fun searchProducts(
        query: String,
        limit: Int?,
    ): List<ProductDto.ProductResponseDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/search")) {
                parameter("q", query)
                limit?.let { parameter("limit", it) }
            }
        }

    override suspend fun createProduct(request: ProductDto.CreateProductRequestDto): ProductDto.ProductResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateProduct(
        id: Long,
        request: ProductDto.UpdateProductRequestDto,
    ): ProductDto.ProductResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deleteProduct(id: Long): String = httpCall.httpClient.apiCall { delete(url("$BASE_ENDPOINT/$id")) }

    override suspend fun syncProducts(lastSyncTime: String): List<ProductDto.ProductResponseDto> =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/sync/$lastSyncTime")) }

    override suspend fun importProductsCsv(
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

    override suspend fun uploadProductImage(
        imageBytes: ByteArray,
        fileName: String,
    ): ProductDto.ImageUploadResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/upload-image")) {
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

    override suspend fun getFavouriteProductIds(): List<Long> {
        val resp: FavouriteProductDto.FavouriteProductListResponseDto =
            httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/favourites")) }
        return resp.products.map { it.productId }
    }
}
