package org.example.project.core.data.remote

import org.example.project.core.domain.dto.product.ChoiceGroupDto

/**
 * Throws-style API client for choice groups.
 */
interface ChoiceGroupApiClient {
    suspend fun getAll(): ChoiceGroupDto.ListResponseDto

    suspend fun getUpdatedAfter(lastSyncTime: String): ChoiceGroupDto.ListResponseDto

    suspend fun getById(id: Int): ChoiceGroupDto.ChoiceGroupResponseDto

    suspend fun create(request: ChoiceGroupDto.CreateChoiceGroupRequestDto): ChoiceGroupDto.ChoiceGroupResponseDto

    suspend fun update(
        id: Int,
        request: ChoiceGroupDto.UpdateChoiceGroupRequestDto,
    ): ChoiceGroupDto.ChoiceGroupResponseDto

    suspend fun delete(id: Int)
}
