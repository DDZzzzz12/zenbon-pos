package org.example.project.core.data.remote

import org.example.project.core.domain.dto.product.ProductChoiceGroupDto

/**
 * Throws-style API client for product↔choice-group attachments.
 */
interface ProductChoiceGroupApiClient {
    suspend fun getAll(): ProductChoiceGroupDto.ListResponseDto

    suspend fun getUpdatedAfter(lastSyncTime: String): ProductChoiceGroupDto.ListResponseDto

    suspend fun getForProduct(productId: Long): ProductChoiceGroupDto.ProductGroupsResponseDto

    suspend fun attach(request: ProductChoiceGroupDto.AttachRequestDto): ProductChoiceGroupDto.ListResponseDto

    suspend fun bulkAttachToCategory(request: ProductChoiceGroupDto.BulkCategoryAttachRequestDto): ProductChoiceGroupDto.BulkAttachResultDto

    suspend fun bulkRemoveFromCategory(
        categoryId: Int,
        groupId: Int,
    )
}
