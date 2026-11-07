package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.ChoiceGroupApiClient
import org.example.project.core.domain.dto.product.ChoiceGroupDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

/**
 * Throws-style Ktor implementation of [ChoiceGroupApiClient].
 */
class KtorChoiceGroupApiClient(
    private val httpCall: HttpCall,
) : ChoiceGroupApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/choice-groups"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getAll(): ChoiceGroupDto.ListResponseDto = httpCall.httpClient.apiCall { get(url(BASE_ENDPOINT)) }

    override suspend fun getUpdatedAfter(lastSyncTime: String): ChoiceGroupDto.ListResponseDto =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/sync/$lastSyncTime")) }

    override suspend fun getById(id: Int): ChoiceGroupDto.ChoiceGroupResponseDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$id")) }

    override suspend fun create(request: ChoiceGroupDto.CreateChoiceGroupRequestDto): ChoiceGroupDto.ChoiceGroupResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun update(
        id: Int,
        request: ChoiceGroupDto.UpdateChoiceGroupRequestDto,
    ): ChoiceGroupDto.ChoiceGroupResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun delete(id: Int) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$BASE_ENDPOINT/$id")) }
    }
}
