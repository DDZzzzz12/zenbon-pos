package org.example.project.features.table.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.table.RoomElementDto
import org.example.project.core.domain.enums.room.RoomElementType
import org.example.project.features.table.domain.models.RoomElement

/**
 * Repository interface for RoomElement entity operations.
 *
 * Provides data access layer for floor plan element management including
 * CRUD operations, type filtering, and location-based queries.
 * Returns plain entities or nullable types; exceptions propagate to the service layer.
 */
interface RoomElementRepository {
    fun getAllElements(
        limit: Int? = null,
        offset: Int? = null,
        elementType: RoomElementType? = null,
        locationTypeId: Long? = null,
    ): List<RoomElement>

    fun getElementById(id: Long): RoomElement?

    fun createElement(request: RoomElementDto.CreateRoomElementRequestDto): RoomElement

    fun updateElement(
        id: Long,
        request: RoomElementDto.UpdateRoomElementRequestDto,
    ): RoomElement?

    fun updateElementPosition(
        id: Long,
        request: RoomElementDto.UpdateRoomElementPositionRequestDto,
    ): RoomElement?

    fun updateElementDimensions(
        id: Long,
        request: RoomElementDto.UpdateRoomElementDimensionsRequestDto,
    ): RoomElement?

    /**
     * Delete a room element (hard delete).
     *
     * @throws NoSuchElementException if element not found
     */
    fun deleteElement(id: Long)

    fun getElementsByType(elementType: RoomElementType): List<RoomElement>

    fun getElementsByLocationTypeId(locationTypeId: Long): List<RoomElement>

    fun getElementsUpdatedAfter(lastSyncTime: LocalDateTime): List<RoomElement>
}
