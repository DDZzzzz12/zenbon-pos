package org.example.project.core.domain.dto.product

import kotlinx.serialization.Serializable

object ProductExtrasAssignmentDto {
    @Serializable
    data class ResponseDto(
        val productId: Long,
        val extrasId: Int,
        val updatedAt: String,
    )

    @Serializable
    data class ListResponseDto(
        val items: List<ResponseDto>,
        val totalCount: Int,
    )

    @Serializable
    data class AssignRequestDto(
        val productId: Long,
        val extrasIds: List<Int>,
    )

    @Serializable
    data class BulkCategoryAssignRequestDto(
        val categoryId: Int,
        val extrasIds: List<Int>,
    )

    @Serializable
    data class BulkAssignResultDto(
        val assigned: Int,
        val skipped: Int,
        val totalProducts: Int,
    )
}
