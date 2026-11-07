package org.example.project.core.data.remote

import org.example.project.core.domain.dto.product.ProductExtrasAssignmentDto

/**
 * Stage C Batch 7: throws-style.
 */
interface ProductExtrasAssignmentApiClient {
    suspend fun getAll(): ProductExtrasAssignmentDto.ListResponseDto

    suspend fun getUpdatedAfter(lastSyncTime: String): ProductExtrasAssignmentDto.ListResponseDto

    suspend fun getForProduct(productId: Long): List<ProductExtrasAssignmentDto.ResponseDto>

    suspend fun assignToProduct(request: ProductExtrasAssignmentDto.AssignRequestDto): List<ProductExtrasAssignmentDto.ResponseDto>

    suspend fun bulkAssignToCategory(request: ProductExtrasAssignmentDto.BulkCategoryAssignRequestDto): ProductExtrasAssignmentDto.BulkAssignResultDto

    suspend fun removeFromProduct(
        productId: Long,
        extrasId: Int,
    )

    suspend fun bulkRemoveFromCategory(
        categoryId: Int,
        extrasId: Int,
    )
}
