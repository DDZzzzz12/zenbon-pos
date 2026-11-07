package org.example.project.core.domain.dto.product

import kotlinx.serialization.Serializable

/** DTOs for choice groups and their options (delta-per-placement). */
object ChoiceGroupDto {
    @Serializable
    data class OptionResponseDto(
        val optionId: Int,
        val name: String,
        val en: String? = null,
        val ch: String? = null,
        val priceDelta: String, // BigDecimal serialized as String
        val sortPosition: Int,
        val isDefault: Boolean,
        val linkedProductId: Long? = null,
    )

    @Serializable
    data class ChoiceGroupResponseDto(
        val id: Int,
        val name: String,
        val en: String? = null,
        val ch: String? = null,
        val minSelections: Int,
        val maxSelections: Int,
        val isActive: Boolean,
        val updatedAt: String,
        val options: List<OptionResponseDto> = emptyList(),
        /**
         * ISO 4217 code every `priceDelta` under this group is denominated in. Supplied by the
         * server from shop config (`ConfigKey.DEFAULT_CURRENCY`). One field per response root —
         * [OptionResponseDto] inherits it rather than repeating it. The default only keeps
         * non-server construction sites compiling; the server mappers set it explicitly.
         */
        val currency: String = "EUR",
    )

    @Serializable
    data class ListResponseDto(
        val items: List<ChoiceGroupResponseDto>,
        val totalCount: Int,
    )

    @Serializable
    data class OptionRequestDto(
        val optionId: Int,
        val priceDelta: String,
        val sortPosition: Int = 0,
        val isDefault: Boolean = false,
    )

    @Serializable
    data class CreateChoiceGroupRequestDto(
        val name: String,
        val en: String? = null,
        val ch: String? = null,
        val minSelections: Int = 0,
        val maxSelections: Int = 1,
        val isActive: Boolean = true,
        val options: List<OptionRequestDto> = emptyList(),
    ) {
        fun validate() {
            require(name.isNotBlank()) { "Choice group name is required" }
            require(name.length <= 255) { "Choice group name must not exceed 255 characters" }
            require(minSelections >= 0) { "minSelections must be >= 0" }
            require(maxSelections >= 1) { "maxSelections must be >= 1" }
            require(minSelections <= maxSelections) { "minSelections must be <= maxSelections" }
            options.forEach { opt ->
                require(opt.optionId > 0) { "optionId must be positive" }
                require(opt.priceDelta.toDoubleOrNull() != null) { "priceDelta must be a valid decimal" }
            }
        }
    }

    @Serializable
    data class UpdateChoiceGroupRequestDto(
        val name: String,
        val en: String? = null,
        val ch: String? = null,
        val minSelections: Int,
        val maxSelections: Int,
        val isActive: Boolean,
        val options: List<OptionRequestDto> = emptyList(),
    ) {
        fun validate() {
            require(name.isNotBlank()) { "Choice group name is required" }
            require(name.length <= 255) { "Choice group name must not exceed 255 characters" }
            require(minSelections >= 0) { "minSelections must be >= 0" }
            require(maxSelections >= 1) { "maxSelections must be >= 1" }
            require(minSelections <= maxSelections) { "minSelections must be <= maxSelections" }
            options.forEach { opt ->
                require(opt.optionId > 0) { "optionId must be positive" }
                require(opt.priceDelta.toDoubleOrNull() != null) { "priceDelta must be a valid decimal" }
            }
        }
    }
}
