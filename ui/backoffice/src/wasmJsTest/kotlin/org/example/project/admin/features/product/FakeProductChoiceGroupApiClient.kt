package org.example.project.admin.features.product

import org.example.project.core.data.remote.ProductChoiceGroupApiClient
import org.example.project.core.domain.dto.product.ProductChoiceGroupDto

/** Hand-written fake; records the last `attach` request and returns a configurable `getForProduct` result. */
open class FakeProductChoiceGroupApiClient(
    var groupsForProduct: ProductChoiceGroupDto.ProductGroupsResponseDto =
        ProductChoiceGroupDto.ProductGroupsResponseDto(productId = 0, groups = emptyList()),
) : ProductChoiceGroupApiClient {
    var lastAttach: ProductChoiceGroupDto.AttachRequestDto? = null
    var lastBulkAttach: ProductChoiceGroupDto.BulkCategoryAttachRequestDto? = null
    var lastBulkRemove: Pair<Int, Int>? = null

    override suspend fun getAll() = ProductChoiceGroupDto.ListResponseDto(emptyList(), 0)

    override suspend fun getUpdatedAfter(lastSyncTime: String) = ProductChoiceGroupDto.ListResponseDto(emptyList(), 0)

    override suspend fun getForProduct(productId: Long) = groupsForProduct

    override suspend fun attach(request: ProductChoiceGroupDto.AttachRequestDto): ProductChoiceGroupDto.ListResponseDto {
        lastAttach = request
        return ProductChoiceGroupDto.ListResponseDto(
            request.groupIds.mapIndexed { i, id ->
                ProductChoiceGroupDto.ResponseDto(productId = request.productId, groupId = id, sortPosition = i, updatedAt = "2026-07-11")
            },
            request.groupIds.size,
        )
    }

    override suspend fun bulkAttachToCategory(request: ProductChoiceGroupDto.BulkCategoryAttachRequestDto): ProductChoiceGroupDto.BulkAttachResultDto {
        lastBulkAttach = request
        return ProductChoiceGroupDto.BulkAttachResultDto(assigned = 0, skipped = 0, totalProducts = 0)
    }

    override suspend fun bulkRemoveFromCategory(
        categoryId: Int,
        groupId: Int,
    ) {
        lastBulkRemove = categoryId to groupId
    }
}
