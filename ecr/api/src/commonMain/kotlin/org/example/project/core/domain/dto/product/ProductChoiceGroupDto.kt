package org.example.project.core.domain.dto.product

import kotlinx.serialization.Serializable

/** DTOs for attaching choice groups to products and reordering them. */
object ProductChoiceGroupDto {
    @Serializable
    data class ResponseDto(
        val productId: Long,
        val groupId: Int,
        val sortPosition: Int,
        val updatedAt: String,
    )

    @Serializable
    data class ListResponseDto(
        val items: List<ResponseDto>,
        val totalCount: Int,
    )

    /** Full resolved view used by the POS configurator: the product's groups with their options. */
    @Serializable
    data class ProductGroupsResponseDto(
        val productId: Long,
        val groups: List<ChoiceGroupDto.ChoiceGroupResponseDto>,
    )

    @Serializable
    data class AttachRequestDto(
        val productId: Long,
        val groupIds: List<Int>, // ordered; index → sortPosition
    ) {
        fun validate() {
            require(productId > 0) { "productId must be positive" }
            require(groupIds.toSet().size == groupIds.size) { "Duplicate group IDs" }
        }
    }

    /** Additive bulk attach: add the given groups to every product in a category. */
    @Serializable
    data class BulkCategoryAttachRequestDto(
        val categoryId: Int,
        val groupIds: List<Int>,
    ) {
        fun validate() {
            require(categoryId > 0) { "categoryId must be positive" }
            require(groupIds.toSet().size == groupIds.size) { "Duplicate group IDs" }
            require(groupIds.all { it > 0 }) { "groupIds must be positive" }
        }
    }

    @Serializable
    data class BulkAttachResultDto(
        val assigned: Int,
        val skipped: Int,
        val totalProducts: Int,
    )
}
