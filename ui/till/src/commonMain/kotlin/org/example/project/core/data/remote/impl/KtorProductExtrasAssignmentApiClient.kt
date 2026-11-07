package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.ProductExtrasAssignmentApiClient
import org.example.project.core.domain.dto.product.ProductExtrasAssignmentDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

/**
 * Stage C Batch 7: throws-style.
 */
class KtorProductExtrasAssignmentApiClient(
    private val httpCall: HttpCall,
) : ProductExtrasAssignmentApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/product-extras-assignment"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getAll(): ProductExtrasAssignmentDto.ListResponseDto = httpCall.httpClient.apiCall { get(url(BASE_ENDPOINT)) }

    override suspend fun getUpdatedAfter(lastSyncTime: String): ProductExtrasAssignmentDto.ListResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/sync/$lastSyncTime")) }

    override suspend fun getForProduct(productId: Long): List<ProductExtrasAssignmentDto.ResponseDto> =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/product/$productId")) }

    override suspend fun assignToProduct(request: ProductExtrasAssignmentDto.AssignRequestDto): List<ProductExtrasAssignmentDto.ResponseDto> =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun bulkAssignToCategory(
        request: ProductExtrasAssignmentDto.BulkCategoryAssignRequestDto,
    ): ProductExtrasAssignmentDto.BulkAssignResultDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/bulk-category")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun removeFromProduct(
        productId: Long,
        extrasId: Int,
    ) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$BASE_ENDPOINT/$productId/$extrasId")) }
    }

    override suspend fun bulkRemoveFromCategory(
        categoryId: Int,
        extrasId: Int,
    ) {
        httpCall.httpClient.apiCall<Unit> {
            delete(url("$BASE_ENDPOINT/bulk-category/$categoryId/$extrasId"))
        }
    }
}
