package org.example.project.features.product.domain.service

import org.example.project.core.domain.dto.product.ChoiceGroupDto

interface ChoiceGroupService {
    suspend fun getAll(includeInactive: Boolean = false): ChoiceGroupDto.ListResponseDto

    suspend fun getById(id: Int): ChoiceGroupDto.ChoiceGroupResponseDto

    suspend fun create(
        employeeId: String,
        request: ChoiceGroupDto.CreateChoiceGroupRequestDto,
    ): ChoiceGroupDto.ChoiceGroupResponseDto

    suspend fun update(
        employeeId: String,
        id: Int,
        request: ChoiceGroupDto.UpdateChoiceGroupRequestDto,
    ): ChoiceGroupDto.ChoiceGroupResponseDto

    suspend fun delete(
        employeeId: String,
        id: Int,
    ): String

    suspend fun getUpdatedAfter(lastSyncTime: String): ChoiceGroupDto.ListResponseDto
}
