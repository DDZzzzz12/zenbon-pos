package org.example.project.features.product.domain.service

import org.example.project.core.domain.dto.product.ProductChoiceGroupDto

interface ProductChoiceGroupService {
    suspend fun getForProduct(productId: Long): ProductChoiceGroupDto.ProductGroupsResponseDto

    suspend fun getAll(): ProductChoiceGroupDto.ListResponseDto

    suspend fun attach(
        employeeId: String,
        request: ProductChoiceGroupDto.AttachRequestDto,
    ): ProductChoiceGroupDto.ListResponseDto

    suspend fun bulkAttachToCategory(
        employeeId: String,
        categoryId: Int,
        groupIds: List<Int>,
    ): ProductChoiceGroupDto.BulkAttachResultDto

    suspend fun bulkRemoveFromCategory(
        employeeId: String,
        categoryId: Int,
        groupId: Int,
    )

    suspend fun getUpdatedAfter(lastSyncTime: String): ProductChoiceGroupDto.ListResponseDto
}
