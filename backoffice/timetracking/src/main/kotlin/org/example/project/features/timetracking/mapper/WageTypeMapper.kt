package org.example.project.features.timetracking.mapper

import org.example.project.core.domain.dto.timetracking.WageTypeDto
import org.example.project.features.timetracking.domain.models.WageType

fun WageType.toResponse(): WageTypeDto.WageTypeResponseDto =
    WageTypeDto.WageTypeResponseDto(
        id = id.value.toString(),
        code = code,
        name = name,
        description = description,
        isDefault = isDefault,
    )

fun List<WageType>.toListResponse(): WageTypeDto.WageTypeListResponseDto =
    WageTypeDto.WageTypeListResponseDto(
        wageTypes = map { it.toResponse() },
        total = size,
    )
