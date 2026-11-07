/**
 * Room Element entity to DTO mapping functions.
 *
 * Provides conversion methods for RoomElement entities to their corresponding DTOs
 * for API responses and data transfer operations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.table.mapper

import org.example.project.core.domain.dto.table.RoomElementDto
import org.example.project.features.table.domain.models.RoomElement

/**
 * Converts RoomElement entity to RoomElementResponseDto.
 */
fun RoomElement.toResponse(): RoomElementDto.RoomElementResponseDto =
    RoomElementDto.RoomElementResponseDto(
        id = this.id.value,
        elementType = this.elementType.name,
        positionX = this.positionX,
        positionY = this.positionY,
        width = this.width,
        height = this.height,
        rotation = this.rotation,
        locationTypeId = this.locationTypeRef.id.value,
        metadata = this.metadata,
    )
