package org.example.project.testutils.fakes

import org.example.project.core.data.remote.ChoiceGroupApiClient
import org.example.project.core.domain.dto.product.ChoiceGroupDto

/**
 * Throws-style fake for [ChoiceGroupApiClient].
 */
class FakeChoiceGroupApiClient : ChoiceGroupApiClient {
    private val emptyGroup =
        ChoiceGroupDto.ChoiceGroupResponseDto(
            id = 0,
            name = "",
            minSelections = 0,
            maxSelections = 1,
            isActive = true,
            updatedAt = "",
        )

    var getAllResult: Result<ChoiceGroupDto.ListResponseDto> =
        Result.success(ChoiceGroupDto.ListResponseDto(items = emptyList(), totalCount = 0))
    var getUpdatedAfterResult: Result<ChoiceGroupDto.ListResponseDto> =
        Result.success(ChoiceGroupDto.ListResponseDto(items = emptyList(), totalCount = 0))
    var getByIdResult: Result<ChoiceGroupDto.ChoiceGroupResponseDto> = Result.success(emptyGroup)
    var createResult: Result<ChoiceGroupDto.ChoiceGroupResponseDto> = Result.success(emptyGroup)
    var updateResult: Result<ChoiceGroupDto.ChoiceGroupResponseDto> = Result.success(emptyGroup)
    var deleteResult: Result<Unit> = Result.success(Unit)

    override suspend fun getAll(): ChoiceGroupDto.ListResponseDto = getAllResult.getOrThrow()

    override suspend fun getUpdatedAfter(lastSyncTime: String): ChoiceGroupDto.ListResponseDto = getUpdatedAfterResult.getOrThrow()

    override suspend fun getById(id: Int): ChoiceGroupDto.ChoiceGroupResponseDto = getByIdResult.getOrThrow()

    override suspend fun create(request: ChoiceGroupDto.CreateChoiceGroupRequestDto): ChoiceGroupDto.ChoiceGroupResponseDto = createResult.getOrThrow()

    override suspend fun update(
        id: Int,
        request: ChoiceGroupDto.UpdateChoiceGroupRequestDto,
    ): ChoiceGroupDto.ChoiceGroupResponseDto = updateResult.getOrThrow()

    override suspend fun delete(id: Int) {
        deleteResult.getOrThrow()
    }
}
