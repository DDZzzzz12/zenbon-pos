package org.example.project.features.pfand.mapper

import org.example.project.core.domain.dto.pfand.PfandTypeDto
import org.example.project.features.pfand.domain.models.PfandType

fun PfandType.toResponse() =
    PfandTypeDto.PfandTypeResponseDto(
        id = id.value,
        name = name,
        depositValue = depositValue.toPlainString(),
        vatClass = vatClass,
        isActive = isActive,
    )
