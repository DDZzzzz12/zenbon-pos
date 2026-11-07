package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.ProductChoiceGroupApiClient
import org.example.project.core.domain.dto.product.ProductChoiceGroupDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

/**
 * Throws-style Ktor implementation of [ProductChoiceGroupApiClient].
 */
class KtorProductChoiceGroupApiClient(
    private val httpCall: HttpCall,
) : ProductChoiceGroupApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/product-choice-groups"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getAll(): ProductChoiceGroupDto.ListResponseDto = httpCall.httpClient.apiCall { get(url(BASE_ENDPOINT)) }

    override suspend fun getUpdatedAfter(lastSyncTime: String): ProductChoiceGroupDto.ListResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/sync/$lastSyncTime")) }

    override suspend fun getForProduct(productId: Long): ProductChoiceGroupDto.ProductGroupsResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/product/$productId")) }

    override suspend fun attach(request: ProductChoiceGroupDto.AttachRequestDto): ProductChoiceGroupDto.ListResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun bulkAttachToCategory(request: ProductChoiceGroupDto.BulkCategoryAttachRequestDto): ProductChoiceGroupDto.BulkAttachResultDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/bulk-category")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun bulkRemoveFromCategory(
        categoryId: Int,
        groupId: Int,
    ) {
        httpCall.httpClient.apiCall<Unit> {
            delete(url("$BASE_ENDPOINT/bulk-category/$categoryId/$groupId"))
        }
    }
}
