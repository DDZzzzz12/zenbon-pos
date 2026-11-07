package org.example.project.testutils.fakes

import org.example.project.core.data.remote.ProductExtrasAssignmentApiClient
import org.example.project.core.domain.dto.product.ProductExtrasAssignmentDto

/**
 * Stage C Batch 7: throws-style fake.
 */
class FakeProductExtrasAssignmentApiClient : ProductExtrasAssignmentApiClient {
    var getAllResult: Result<ProductExtrasAssignmentDto.ListResponseDto> =
        Result.success(
            ProductExtrasAssignmentDto.ListResponseDto(items = emptyList(), totalCount = 0),
        )
    var getUpdatedAfterResult: Result<ProductExtrasAssignmentDto.ListResponseDto> =
        Result.success(
            ProductExtrasAssignmentDto.ListResponseDto(items = emptyList(), totalCount = 0),
        )
    var getForProductResult: Result<List<ProductExtrasAssignmentDto.ResponseDto>> = Result.success(emptyList())
    var assignToProductResult: Result<List<ProductExtrasAssignmentDto.ResponseDto>> = Result.success(emptyList())
    var bulkAssignToCategoryResult: Result<ProductExtrasAssignmentDto.BulkAssignResultDto> =
        Result.success(
            ProductExtrasAssignmentDto.BulkAssignResultDto(assigned = 0, skipped = 0, totalProducts = 0),
        )
    var removeFromProductResult: Result<Unit> = Result.success(Unit)
    var bulkRemoveFromCategoryResult: Result<Unit> = Result.success(Unit)

    var getAllCallCount = 0
    var getForProductCallCount = 0
    var assignToProductCallCount = 0
    var removeFromProductCallCount = 0
    var bulkAssignToCategoryCallCount = 0
    var bulkRemoveFromCategoryCallCount = 0

    override suspend fun getAll(): ProductExtrasAssignmentDto.ListResponseDto {
        getAllCallCount++
        return getAllResult.getOrThrow()
    }

    override suspend fun getUpdatedAfter(lastSyncTime: String): ProductExtrasAssignmentDto.ListResponseDto = getUpdatedAfterResult.getOrThrow()

    override suspend fun getForProduct(productId: Long): List<ProductExtrasAssignmentDto.ResponseDto> {
        getForProductCallCount++
        return getForProductResult.getOrThrow()
    }

    override suspend fun assignToProduct(request: ProductExtrasAssignmentDto.AssignRequestDto): List<ProductExtrasAssignmentDto.ResponseDto> {
        assignToProductCallCount++
        return assignToProductResult.getOrThrow()
    }

    override suspend fun bulkAssignToCategory(
        request: ProductExtrasAssignmentDto.BulkCategoryAssignRequestDto,
    ): ProductExtrasAssignmentDto.BulkAssignResultDto {
        bulkAssignToCategoryCallCount++
        return bulkAssignToCategoryResult.getOrThrow()
    }

    override suspend fun removeFromProduct(
        productId: Long,
        extrasId: Int,
    ) {
        removeFromProductCallCount++
        removeFromProductResult.getOrThrow()
    }

    override suspend fun bulkRemoveFromCategory(
        categoryId: Int,
        extrasId: Int,
    ) {
        bulkRemoveFromCategoryCallCount++
        bulkRemoveFromCategoryResult.getOrThrow()
    }
}
