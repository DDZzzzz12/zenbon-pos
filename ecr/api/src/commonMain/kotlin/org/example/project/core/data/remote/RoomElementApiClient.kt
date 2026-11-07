/**
 * API client interface for room element operations.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.table.RoomElementDto

interface RoomElementApiClient {
    suspend fun getAllRoomElements(
        type: String? = null,
        locationId: Long? = null,
        lastUpdateTime: String? = null,
    ): RoomElementDto.RoomElementListResponseDto

    suspend fun getRoomElementById(id: Long): RoomElementDto.RoomElementResponseDto

    // NOTE: the /by-type and /by-location server endpoints respond with a bare JSON array
    // (the service returns List<RoomElementResponseDto>), NOT the RoomElementListResponseDto
    // wrapper that getAllRoomElements returns. The return types here must stay List<...>.
    suspend fun getRoomElementsByType(type: String): List<RoomElementDto.RoomElementResponseDto>

    suspend fun getRoomElementsByLocation(locationId: Long): List<RoomElementDto.RoomElementResponseDto>

    suspend fun createRoomElement(request: RoomElementDto.CreateRoomElementRequestDto): RoomElementDto.RoomElementResponseDto

    suspend fun updateRoomElement(
        id: Long,
        request: RoomElementDto.UpdateRoomElementRequestDto,
    ): RoomElementDto.RoomElementResponseDto

    suspend fun updateRoomElementPosition(
        id: Long,
        request: RoomElementDto.UpdateRoomElementPositionRequestDto,
    ): RoomElementDto.RoomElementResponseDto

    suspend fun updateRoomElementDimensions(
        id: Long,
        request: RoomElementDto.UpdateRoomElementDimensionsRequestDto,
    ): RoomElementDto.RoomElementResponseDto

    suspend fun deleteRoomElement(id: Long)

    suspend fun syncRoomElements(lastSyncTime: String): List<RoomElementDto.RoomElementResponseDto>
}
