package org.example.project.features.table.domain.service

import org.example.project.core.domain.dto.table.LocationTypeDto

interface LocationTypeService {
    suspend fun getAll(): LocationTypeDto.ListResponseDto

    suspend fun getById(id: Long): LocationTypeDto.ResponseDto

    suspend fun create(request: LocationTypeDto.CreateRequestDto): LocationTypeDto.ResponseDto

    suspend fun update(
        id: Long,
        request: LocationTypeDto.UpdateRequestDto,
    ): LocationTypeDto.ResponseDto

    suspend fun delete(id: Long)
}
