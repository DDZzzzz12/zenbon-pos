package org.example.project.admin.features.choicegroup

import org.example.project.core.data.remote.ChoiceGroupApiClient
import org.example.project.core.domain.dto.product.ChoiceGroupDto

class FakeChoiceGroupApiClient(
    var groups: List<ChoiceGroupDto.ChoiceGroupResponseDto> = emptyList(),
) : ChoiceGroupApiClient {
    var created: ChoiceGroupDto.CreateChoiceGroupRequestDto? = null
    var updatedId: Int? = null
    var updated: ChoiceGroupDto.UpdateChoiceGroupRequestDto? = null
    var deletedId: Int? = null

    override suspend fun getAll() = ChoiceGroupDto.ListResponseDto(groups, groups.size)

    override suspend fun getUpdatedAfter(lastSyncTime: String) = ChoiceGroupDto.ListResponseDto(groups, groups.size)

    override suspend fun getById(id: Int) = groups.first { it.id == id }

    override suspend fun create(request: ChoiceGroupDto.CreateChoiceGroupRequestDto): ChoiceGroupDto.ChoiceGroupResponseDto {
        created = request
        return ChoiceGroupDto.ChoiceGroupResponseDto(
            88,
            request.name,
            request.en,
            request.ch,
            request.minSelections,
            request.maxSelections,
            request.isActive,
            "2026-07-11",
            emptyList(),
        )
    }

    override suspend fun update(
        id: Int,
        request: ChoiceGroupDto.UpdateChoiceGroupRequestDto,
    ): ChoiceGroupDto.ChoiceGroupResponseDto {
        updatedId = id
        updated = request
        return ChoiceGroupDto.ChoiceGroupResponseDto(
            id,
            request.name,
            request.en,
            request.ch,
            request.minSelections,
            request.maxSelections,
            request.isActive,
            "2026-07-11",
            emptyList(),
        )
    }

    override suspend fun delete(id: Int) {
        deletedId = id
    }
}
