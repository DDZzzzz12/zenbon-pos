package org.example.project.testutils.fakes

import org.example.project.core.domain.dto.table.LocationTypeDto
import org.example.project.features.settings.data.remote.LocationTypeApiClient

/** [LocationTypeApiClient] fake that records creates; [failure] makes a create throw. */
class FakeLocationTypeApiClient : LocationTypeApiClient {
    val created = mutableListOf<LocationTypeDto.CreateRequestDto>()
    var failure: Exception? = null

    override suspend fun getAllLocationTypes(): LocationTypeDto.ListResponseDto = LocationTypeDto.ListResponseDto(emptyList())

    override suspend fun getLocationTypeById(id: Long) = throw NotImplementedError()

    override suspend fun createLocationType(request: LocationTypeDto.CreateRequestDto): LocationTypeDto.ResponseDto {
        created += request
        failure?.let { throw it }
        return LocationTypeDto.ResponseDto(
            id = created.size.toLong(),
            name = request.name,
            englishName = request.englishName,
            chineseName = request.chineseName,
            isVirtual = request.isVirtual,
            isTakeaway = request.isTakeaway,
            sortOrder = request.sortOrder,
        )
    }

    override suspend fun updateLocationType(
        id: Long,
        request: LocationTypeDto.UpdateRequestDto,
    ) = throw NotImplementedError()

    override suspend fun deleteLocationType(id: Long) = throw NotImplementedError()
}
