package org.example.project.core.domain.dto.table

import kotlinx.serialization.Serializable

object LocationTypeDto {
    @Serializable
    data class ResponseDto(
        val id: Long,
        val name: String,
        val englishName: String,
        val chineseName: String,
        val isVirtual: Boolean,
        val isTakeaway: Boolean,
        val sortOrder: Int,
        /** System-provisioned (the virtual Gutschein/Schnellverkauf types): read-only for operators. */
        val isSystem: Boolean = false,
    )

    @Serializable
    data class ListResponseDto(
        val locationTypes: List<ResponseDto>,
    )

    @Serializable
    data class CreateRequestDto(
        val name: String,
        val englishName: String,
        val chineseName: String,
        val isVirtual: Boolean = false,
        val isTakeaway: Boolean = false,
        val sortOrder: Int = 0,
    )

    @Serializable
    data class UpdateRequestDto(
        val name: String? = null,
        val englishName: String? = null,
        val chineseName: String? = null,
        val isVirtual: Boolean? = null,
        val isTakeaway: Boolean? = null,
        val sortOrder: Int? = null,
    )
}
