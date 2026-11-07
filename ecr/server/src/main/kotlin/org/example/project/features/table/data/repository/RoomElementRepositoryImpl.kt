package org.example.project.features.table.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.table.RoomElementDto
import org.example.project.core.domain.enums.room.RoomElementType
import org.example.project.features.table.database.tables.RoomElementTable
import org.example.project.features.table.domain.models.LocationType
import org.example.project.features.table.domain.models.RoomElement
import org.example.project.features.table.domain.repository.RoomElementRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.slf4j.LoggerFactory

class RoomElementRepositoryImpl : RoomElementRepository {
    private val logger = LoggerFactory.getLogger(RoomElementRepositoryImpl::class.java)

    override fun getAllElements(
        limit: Int?,
        offset: Int?,
        elementType: RoomElementType?,
        locationTypeId: Long?,
    ): List<RoomElement> {
        var query = RoomElement.Companion.all()

        // Apply element type filter if provided
        elementType?.let {
            query = RoomElement.Companion.find { RoomElementTable.elementType eq elementType }
        }

        // Apply location type filter if provided
        locationTypeId?.let {
            query =
                if (elementType != null) {
                    RoomElement.Companion.find {
                        (RoomElementTable.elementType eq elementType) and (RoomElementTable.locationTypeId eq locationTypeId)
                    }
                } else {
                    RoomElement.Companion.find { RoomElementTable.locationTypeId eq locationTypeId }
                }
        }

        if (offset != null) {
            if (offset > 0) {
                query = query.limit(count = limit ?: Int.MAX_VALUE).offset(start = offset.toLong())
            }
        } else if (limit != null) {
            query = query.limit(limit)
        }

        val elements = query.toList()
        logger.debug(
            "Retrieved ${elements.size} room elements with limit=$limit, offset=$offset, type=$elementType, locationTypeId=$locationTypeId",
        )
        return elements
    }

    override fun getElementById(id: Long): RoomElement? {
        val element = RoomElement.Companion.findById(id)
        if (element != null) {
            logger.debug("Found room element with id: $id")
        } else {
            logger.warn("Room element not found with id: $id")
        }
        return element
    }

    override fun createElement(request: RoomElementDto.CreateRoomElementRequestDto): RoomElement {
        // Validate request
        request.validate()

        val locationType =
            LocationType.findById(request.locationTypeId)
                ?: throw NoSuchElementException("LocationType not found: ${request.locationTypeId}")

        val element =
            RoomElement.Companion.new {
                elementType = request.getElementType()
                positionX = request.positionX
                positionY = request.positionY
                width = request.width
                height = request.height
                rotation = request.rotation
                locationTypeRef = locationType
                metadata = request.metadata
                createdAt = currentTimeStamp()
                updatedAt = currentTimeStamp()
            }

        logger.info("Created new room element: ${element.elementType} at (${element.positionX}, ${element.positionY})")
        return element
    }

    override fun updateElement(
        id: Long,
        request: RoomElementDto.UpdateRoomElementRequestDto,
    ): RoomElement? {
        // Validate request
        request.validate()

        val element = RoomElement.Companion.findById(id)
        if (element == null) {
            logger.warn("Room element not found for update with id: $id")
            return null
        }

        // Update fields if provided
        request.getElementType()?.let { element.elementType = it }
        request.positionX?.let { element.positionX = it }
        request.positionY?.let { element.positionY = it }
        request.width?.let { element.width = it }
        request.height?.let { element.height = it }
        request.rotation?.let { element.rotation = it }
        request.locationTypeId?.let { ltId ->
            val locationType =
                LocationType.findById(ltId)
                    ?: throw NoSuchElementException("LocationType not found: $ltId")
            element.locationTypeRef = locationType
        }
        request.metadata?.let { element.metadata = it }
        element.updatedAt = currentTimeStamp()

        logger.info("Updated room element: ${element.id.value}")
        return element
    }

    override fun updateElementPosition(
        id: Long,
        request: RoomElementDto.UpdateRoomElementPositionRequestDto,
    ): RoomElement? {
        // Validate request
        request.validate()

        val element = RoomElement.Companion.findById(id)
        if (element == null) {
            logger.warn("Room element not found for position update with id: $id")
            return null
        }

        // Use the entity's updatePosition method
        element.updatePosition(request.positionX, request.positionY)
        request.rotation?.let { element.rotation = it }

        logger.info("Updated room element position: ${element.id.value} to (${request.positionX}, ${request.positionY})")
        return element
    }

    override fun updateElementDimensions(
        id: Long,
        request: RoomElementDto.UpdateRoomElementDimensionsRequestDto,
    ): RoomElement? {
        // Validate request
        request.validate()

        val element = RoomElement.Companion.findById(id)
        if (element == null) {
            logger.warn("Room element not found for dimensions update with id: $id")
            return null
        }

        // Use the entity's updateDimensions method
        element.updateDimensions(request.width, request.height)

        logger.info("Updated room element dimensions: ${element.id.value} to ${request.width}x${request.height}")
        return element
    }

    override fun deleteElement(id: Long) {
        val element =
            RoomElement.Companion.findById(id)
                ?: throw NoSuchElementException("Room element not found: $id")

        element.delete()
    }

    override fun getElementsByType(elementType: RoomElementType): List<RoomElement> {
        val elements = RoomElement.Companion.find { RoomElementTable.elementType eq elementType }.toList()
        logger.debug("Found ${elements.size} room elements with type: $elementType")
        return elements
    }

    override fun getElementsByLocationTypeId(locationTypeId: Long): List<RoomElement> {
        val elements = RoomElement.find { RoomElementTable.locationTypeId eq locationTypeId }.toList()
        logger.debug("Found ${elements.size} room elements for locationTypeId: $locationTypeId")
        return elements
    }

    override fun getElementsUpdatedAfter(lastSyncTime: LocalDateTime): List<RoomElement> {
        val elements =
            RoomElement.Companion
                .find { RoomElementTable.updatedAt greater lastSyncTime }
                .toList()

        logger.debug("Found ${elements.size} room elements updated after $lastSyncTime")
        return elements
    }
}
