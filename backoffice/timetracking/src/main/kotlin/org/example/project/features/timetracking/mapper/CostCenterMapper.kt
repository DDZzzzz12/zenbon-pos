package org.example.project.features.timetracking.mapper

import org.example.project.core.domain.dto.timetracking.CostCenterDto
import org.example.project.features.timetracking.domain.models.CostCenter

fun CostCenter.toResponse(): CostCenterDto.CostCenterResponseDto =
    CostCenterDto.CostCenterResponseDto(
        id = id.value.toString(),
        code = code,
        name = name,
        isActive = isActive,
    )

fun List<CostCenter>.toListResponse(): CostCenterDto.CostCenterListResponseDto =
    CostCenterDto.CostCenterListResponseDto(
        costCenters = map { it.toResponse() },
        total = size,
    )
