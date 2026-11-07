/**
 * Ktor-based implementation of RoomElementApiClient.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.RoomElementApiClient
import org.example.project.core.domain.dto.table.RoomElementDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorRoomElementApiClient(
    private val httpCall: HttpCall,
) : RoomElementApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/room-elements"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getAllRoomElements(
        type: String?,
        locationId: Long?,
        lastUpdateTime: String?,
    ): RoomElementDto.RoomElementListResponseDto =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT)) {
                type?.let { parameter("type", it) }
                locationId?.let { parameter("locationId", it) }
                lastUpdateTime?.let { parameter("lastUpdateTime", it) }
            }
        }

    override suspend fun getRoomElementById(id: Long): RoomElementDto.RoomElementResponseDto = httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/$id")) }

    override suspend fun getRoomElementsByType(type: String): List<RoomElementDto.RoomElementResponseDto> =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/by-type/$type")) }

    override suspend fun getRoomElementsByLocation(locationId: Long): List<RoomElementDto.RoomElementResponseDto> =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/by-location/$locationId")) }

    override suspend fun createRoomElement(request: RoomElementDto.CreateRoomElementRequestDto): RoomElementDto.RoomElementResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateRoomElement(
        id: Long,
        request: RoomElementDto.UpdateRoomElementRequestDto,
    ): RoomElementDto.RoomElementResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateRoomElementPosition(
        id: Long,
        request: RoomElementDto.UpdateRoomElementPositionRequestDto,
    ): RoomElementDto.RoomElementResponseDto =
        httpCall.httpClient.apiCall {
            patch(url("$BASE_ENDPOINT/$id/position")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateRoomElementDimensions(
        id: Long,
        request: RoomElementDto.UpdateRoomElementDimensionsRequestDto,
    ): RoomElementDto.RoomElementResponseDto =
        httpCall.httpClient.apiCall {
            patch(url("$BASE_ENDPOINT/$id/dimensions")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun deleteRoomElement(id: Long) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$BASE_ENDPOINT/$id")) }
    }

    override suspend fun syncRoomElements(lastSyncTime: String): List<RoomElementDto.RoomElementResponseDto> =
        httpCall.httpClient.apiCall { get(url("$BASE_ENDPOINT/sync/$lastSyncTime")) }
}
