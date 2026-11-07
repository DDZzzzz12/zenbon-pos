package org.example.project.features.openinghours.mapper

import org.example.project.core.domain.dto.openinghours.OpeningHoursDto
import org.example.project.features.openinghours.domain.models.OpeningHours
import org.example.project.features.openinghours.domain.models.OpeningHoursOverride

fun OpeningHours.toResponse(): OpeningHoursDto.WeeklyResponseDto =
    OpeningHoursDto.WeeklyResponseDto(
        id = this.id.value,
        dayOfWeek = this.dayOfWeek,
        openTime = this.openTime.toString(),
        closeTime = this.closeTime.toString(),
        isClosed = this.isClosed,
        shopId = this.shopId.value,
        createdAt = this.createdAt.toString(),
    )

fun OpeningHoursOverride.toResponse(): OpeningHoursDto.OverrideResponseDto =
    OpeningHoursDto.OverrideResponseDto(
        id = this.id.value,
        overrideDate = this.overrideDate.toString(),
        openTime = this.openTime?.toString(),
        closeTime = this.closeTime?.toString(),
        isClosed = this.isClosed,
        reason = this.reason,
        shopId = this.shopId.value,
        createdAt = this.createdAt.toString(),
    )
