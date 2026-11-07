package org.example.project.core.domain.dto.timetracking

import kotlinx.serialization.Serializable

object CostCenterDto {
    @Serializable
    data class CostCenterResponseDto(
        val id: String,
        val code: String,
        val name: String,
        val isActive: Boolean,
    )

    @Serializable
    data class CreateCostCenterRequestDto(
        val code: String,
        val name: String,
        val isActive: Boolean = true,
    ) {
        fun validate() {
            require(code.isNotBlank()) { "Cost center code is required" }
            require(code.length <= 20) { "Cost center code must be 20 characters or less" }
            require(name.isNotBlank()) { "Cost center name is required" }
        }
    }

    @Serializable
    data class UpdateCostCenterRequestDto(
        val code: String? = null,
        val name: String? = null,
        val isActive: Boolean? = null,
    )

    @Serializable
    data class CostCenterListResponseDto(
        val costCenters: List<CostCenterResponseDto>,
        val total: Int,
    )
}
