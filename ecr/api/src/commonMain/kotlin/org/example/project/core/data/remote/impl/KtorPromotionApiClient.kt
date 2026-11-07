package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.PromotionApiClient
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

/**
 * Stage C Batch 7: throws-style.
 */
class KtorPromotionApiClient(
    private val httpCall: HttpCall,
) : PromotionApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/promotions"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getActivePromotions(): List<PromotionDto.PromotionResponseDto> = httpCall.httpClient.apiCall { get(url(BASE_ENDPOINT)) }

    override suspend fun getAllPromotions(): List<PromotionDto.PromotionResponseDto> = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/all")) }

    override suspend fun getPromotionById(id: Long): PromotionDto.PromotionResponseDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$id")) }

    override suspend fun createPromotion(request: PromotionDto.CreatePromotionRequestDto): PromotionDto.PromotionResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updatePromotion(
        id: Long,
        request: PromotionDto.UpdatePromotionRequestDto,
    ): PromotionDto.PromotionResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deactivatePromotion(id: Long) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$BASE_ENDPOINT/$id")) }
    }

    override suspend fun getPromotionUsage(id: Long): PromotionDto.PromotionUsageDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$id/usage")) }
}
