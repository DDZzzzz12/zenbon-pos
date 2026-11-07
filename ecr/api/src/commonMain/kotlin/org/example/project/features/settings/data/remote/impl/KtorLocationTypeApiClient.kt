package org.example.project.features.settings.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.domain.dto.table.LocationTypeDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall
import org.example.project.features.settings.data.remote.LocationTypeApiClient

class KtorLocationTypeApiClient(
    private val httpCall: HttpCall,
) : LocationTypeApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/location-types"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getAllLocationTypes(): LocationTypeDto.ListResponseDto = httpCall.httpClient.apiCall { get(url(BASE_ENDPOINT)) }

    override suspend fun getLocationTypeById(id: Long): LocationTypeDto.ResponseDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$id")) }

    override suspend fun createLocationType(request: LocationTypeDto.CreateRequestDto): LocationTypeDto.ResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateLocationType(
        id: Long,
        request: LocationTypeDto.UpdateRequestDto,
    ): LocationTypeDto.ResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deleteLocationType(id: Long) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$BASE_ENDPOINT/$id")) }
    }
}
