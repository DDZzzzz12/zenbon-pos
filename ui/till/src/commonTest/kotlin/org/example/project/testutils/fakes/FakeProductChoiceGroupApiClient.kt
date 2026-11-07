package org.example.project.testutils.fakes

import org.example.project.core.data.remote.ProductChoiceGroupApiClient
import org.example.project.core.domain.dto.product.ProductChoiceGroupDto

/**
 * Throws-style fake for [ProductChoiceGroupApiClient].
 */
class FakeProductChoiceGroupApiClient : ProductChoiceGroupApiClient {
    var getAllResult: Result<ProductChoiceGroupDto.ListResponseDto> =
        Result.success(ProductChoiceGroupDto.ListResponseDto(items = emptyList(), totalCount = 0))
    var getUpdatedAfterResult: Result<ProductChoiceGroupDto.ListResponseDto> =
        Result.success(ProductChoiceGroupDto.ListResponseDto(items = emptyList(), totalCount = 0))
    var getForProductResult: Result<ProductChoiceGroupDto.ProductGroupsResponseDto> =
        Result.success(ProductChoiceGroupDto.ProductGroupsResponseDto(productId = 0, groups = emptyList()))
    var attachResult: Result<ProductChoiceGroupDto.ListResponseDto> =
        Result.success(ProductChoiceGroupDto.ListResponseDto(items = emptyList(), totalCount = 0))
    var bulkAttachToCategoryResult: Result<ProductChoiceGroupDto.BulkAttachResultDto> =
        Result.success(ProductChoiceGroupDto.BulkAttachResultDto(assigned = 0, skipped = 0, totalProducts = 0))

    override suspend fun getAll(): ProductChoiceGroupDto.ListResponseDto = getAllResult.getOrThrow()

    override suspend fun getUpdatedAfter(lastSyncTime: String): ProductChoiceGroupDto.ListResponseDto = getUpdatedAfterResult.getOrThrow()

    override suspend fun getForProduct(productId: Long): ProductChoiceGroupDto.ProductGroupsResponseDto = getForProductResult.getOrThrow()

    override suspend fun attach(request: ProductChoiceGroupDto.AttachRequestDto): ProductChoiceGroupDto.ListResponseDto = attachResult.getOrThrow()

    override suspend fun bulkAttachToCategory(request: ProductChoiceGroupDto.BulkCategoryAttachRequestDto): ProductChoiceGroupDto.BulkAttachResultDto =
        bulkAttachToCategoryResult.getOrThrow()

    override suspend fun bulkRemoveFromCategory(
        categoryId: Int,
        groupId: Int,
    ) {
        // no-op stub
    }
}
