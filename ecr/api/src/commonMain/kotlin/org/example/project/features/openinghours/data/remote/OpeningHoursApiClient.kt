package org.example.project.features.openinghours.data.remote

import org.example.project.core.domain.dto.openinghours.OpeningHoursDto

interface OpeningHoursApiClient {
    suspend fun getAll(): List<OpeningHoursDto.WeeklyResponseDto>

    suspend fun bulkUpdate(request: OpeningHoursDto.BulkUpdateRequestDto): List<OpeningHoursDto.WeeklyResponseDto>

    suspend fun getOverridesByDateRange(
        startDate: String,
        endDate: String,
    ): List<OpeningHoursDto.OverrideResponseDto>

    suspend fun createOverride(request: OpeningHoursDto.CreateOverrideRequestDto): OpeningHoursDto.OverrideResponseDto

    suspend fun updateOverride(
        id: Long,
        request: OpeningHoursDto.UpdateOverrideRequestDto,
    ): OpeningHoursDto.OverrideResponseDto

    suspend fun deleteOverride(id: Long)
}
