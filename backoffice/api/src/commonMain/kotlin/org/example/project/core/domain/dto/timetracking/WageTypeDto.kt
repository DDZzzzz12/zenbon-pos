package org.example.project.core.domain.dto.timetracking

import kotlinx.serialization.Serializable

object WageTypeDto {
    @Serializable
    data class WageTypeResponseDto(
        val id: String,
        val code: Int,
        val name: String,
        val description: String?,
        val isDefault: Boolean,
    )

    @Serializable
    data class CreateWageTypeRequestDto(
        val code: Int,
        val name: String,
        val description: String? = null,
        val isDefault: Boolean = false,
    ) {
        fun validate() {
            require(code > 0) { "Wage type code must be positive" }
            require(name.isNotBlank()) { "Wage type name is required" }
        }
    }

    @Serializable
    data class UpdateWageTypeRequestDto(
        val code: Int? = null,
        val name: String? = null,
        val description: String? = null,
        val isDefault: Boolean? = null,
    )

    @Serializable
    data class WageTypeListResponseDto(
        val wageTypes: List<WageTypeResponseDto>,
        val total: Int,
    )
}
