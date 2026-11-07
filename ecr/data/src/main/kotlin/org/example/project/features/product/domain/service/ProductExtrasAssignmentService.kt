package org.example.project.features.product.domain.service

import org.example.project.core.domain.dto.product.ProductExtrasAssignmentDto

/**
 * Service interface for ProductExtrasAssignment business operations.
 *
 * Migrated to throws-style in Stage B Batch 10. Methods return values directly
 * and throw [org.example.project.infrastructure.errors.PosException] on errors.
 */
interface ProductExtrasAssignmentService {
    suspend fun getAllAssignments(includeInactive: Boolean? = null): ProductExtrasAssignmentDto.ListResponseDto

    suspend fun getAssignmentsForProduct(productId: Long): List<ProductExtrasAssignmentDto.ResponseDto>

    suspend fun assignExtrasToProduct(
        employeeId: String,
        productId: Long,
        extrasIds: List<Int>,
    ): List<ProductExtrasAssignmentDto.ResponseDto>

    suspend fun bulkAssignExtrasToCategory(
        employeeId: String,
        categoryId: Int,
        extrasIds: List<Int>,
    ): ProductExtrasAssignmentDto.BulkAssignResultDto

    suspend fun removeExtrasFromProduct(
        employeeId: String,
        productId: Long,
        extrasId: Int,
    )

    suspend fun bulkRemoveExtrasFromCategory(
        employeeId: String,
        categoryId: Int,
        extrasId: Int,
    )

    suspend fun getAssignmentsUpdatedAfter(lastSyncTime: String): ProductExtrasAssignmentDto.ListResponseDto
}
