package org.example.project.features.settings

import org.example.project.core.domain.dto.openinghours.OpeningHoursDto
import org.example.project.features.openinghours.data.remote.OpeningHoursApiClient

/** Records the bulk-update and override-create requests; every other call returns empty. */
internal class RecordingOpeningHoursApi : OpeningHoursApiClient {
    var bulk: OpeningHoursDto.BulkUpdateRequestDto? = null
    var createdOverride: OpeningHoursDto.CreateOverrideRequestDto? = null

    override suspend fun getAll(): List<OpeningHoursDto.WeeklyResponseDto> = emptyList()

    override suspend fun bulkUpdate(request: OpeningHoursDto.BulkUpdateRequestDto): List<OpeningHoursDto.WeeklyResponseDto> {
        bulk = request
        return emptyList()
    }

    override suspend fun getOverridesByDateRange(
        startDate: String,
        endDate: String,
    ): List<OpeningHoursDto.OverrideResponseDto> = emptyList()

    override suspend fun createOverride(request: OpeningHoursDto.CreateOverrideRequestDto): OpeningHoursDto.OverrideResponseDto {
        createdOverride = request
        return OpeningHoursDto.OverrideResponseDto(
            id = 1,
            overrideDate = request.overrideDate,
            openTime = request.openTime,
            closeTime = request.closeTime,
            isClosed = request.isClosed,
            reason = request.reason,
            createdAt = "2026-10-03T12:00:00",
        )
    }

    override suspend fun updateOverride(
        id: Long,
        request: OpeningHoursDto.UpdateOverrideRequestDto,
    ): OpeningHoursDto.OverrideResponseDto = error("unused")

    override suspend fun deleteOverride(id: Long) = Unit
}
