package org.example.project.features.table.domain.service

import org.example.project.core.domain.dto.table.RoomElementDto
import org.example.project.core.domain.enums.room.RoomElementType

/**
 * Service interface for room element business operations.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors.
 */
interface RoomElementService {
    suspend fun getAllElements(
        limit: Int?,
        offset: Int?,
        elementType: RoomElementType? = null,
        locationTypeId: Long? = null,
    ): RoomElementDto.RoomElementListResponseDto

    suspend fun getElementById(id: Long): RoomElementDto.RoomElementResponseDto

    suspend fun createElement(
        employeeId: String,
        request: RoomElementDto.CreateRoomElementRequestDto,
    ): RoomElementDto.RoomElementResponseDto

    suspend fun updateElement(
        employeeId: String,
        id: Long,
        request: RoomElementDto.UpdateRoomElementRequestDto,
    ): RoomElementDto.RoomElementResponseDto

    suspend fun updateElementPosition(
        id: Long,
        request: RoomElementDto.UpdateRoomElementPositionRequestDto,
    ): RoomElementDto.RoomElementResponseDto

    suspend fun updateElementDimensions(
        id: Long,
        request: RoomElementDto.UpdateRoomElementDimensionsRequestDto,
    ): RoomElementDto.RoomElementResponseDto

    suspend fun deleteElement(
        employeeId: String,
        id: Long,
    )

    suspend fun getElementsByType(elementType: RoomElementType): List<RoomElementDto.RoomElementResponseDto>

    suspend fun getElementsByLocationTypeId(locationTypeId: Long): List<RoomElementDto.RoomElementResponseDto>

    suspend fun getElementsUpdatedAfter(lastSyncTime: String): List<RoomElementDto.RoomElementResponseDto>
}
