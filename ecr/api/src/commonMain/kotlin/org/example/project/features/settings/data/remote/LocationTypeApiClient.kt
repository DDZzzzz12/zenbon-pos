package org.example.project.features.settings.data.remote

import org.example.project.core.domain.dto.table.LocationTypeDto

/**
 * Location-type API client.
 *
 * Implementations throw [org.example.project.core.network.error.PosApiException] on
 * non-2xx responses (decoded RFC 7807 ProblemDetail) and
 * [org.example.project.core.network.error.NetworkApiException] on transport failures.
 * VM call sites wrap calls in `runCatchingApi { ... }.onError { _error.value = it }`.
 */
interface LocationTypeApiClient {
    suspend fun getAllLocationTypes(): LocationTypeDto.ListResponseDto

    suspend fun getLocationTypeById(id: Long): LocationTypeDto.ResponseDto

    suspend fun createLocationType(request: LocationTypeDto.CreateRequestDto): LocationTypeDto.ResponseDto

    suspend fun updateLocationType(
        id: Long,
        request: LocationTypeDto.UpdateRequestDto,
    ): LocationTypeDto.ResponseDto

    suspend fun deleteLocationType(id: Long)
}
