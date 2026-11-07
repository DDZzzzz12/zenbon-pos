package org.example.project.core.data.models

import org.example.project.core.domain.dto.product.ChoiceGroupDto
import org.example.project.money.Decimal

data class ChoiceOption(
    val optionId: Int,
    val name: String,
    val enName: String? = null,
    val chName: String? = null,
    val priceDelta: Decimal,
    val sortPosition: Int,
    val isDefault: Boolean,
    val linkedProductId: Long? = null,
)

data class ChoiceGroup(
    val id: Int,
    val name: String,
    val enName: String? = null,
    val chName: String? = null,
    val minSelections: Int,
    val maxSelections: Int,
    val options: List<ChoiceOption>,
) {
    val isRequired get() = minSelections >= 1
    val isSingleSelect get() = maxSelections == 1

    companion object {
        fun fromDto(dto: ChoiceGroupDto.ChoiceGroupResponseDto) =
            ChoiceGroup(
                id = dto.id,
                name = dto.name,
                enName = dto.en,
                chName = dto.ch,
                minSelections = dto.minSelections,
                maxSelections = dto.maxSelections,
                options =
                    dto.options.map { o ->
                        ChoiceOption(
                            optionId = o.optionId,
                            name = o.name,
                            enName = o.en,
                            chName = o.ch,
                            priceDelta = Decimal.parseOrNull(o.priceDelta) ?: Decimal.ZERO,
                            sortPosition = o.sortPosition,
                            isDefault = o.isDefault,
                            linkedProductId = o.linkedProductId,
                        )
                    },
            )
    }
}
