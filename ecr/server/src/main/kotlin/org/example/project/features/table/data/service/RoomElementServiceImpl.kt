/**
 * Implementation of RoomElementService.
 *
 * Throws [PosException] on errors. Migrated from Result<T, Error> in Stage B Batch 10.
 */
package org.example.project.features.table.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.table.RoomElementDto
import org.example.project.core.domain.enums.room.RoomElementType
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.domain.enums.websocket.DataChangeOperation
import org.example.project.core.error.PosErrorCode
import org.example.project.core.websocket.NotificationService
import org.example.project.features.table.domain.repository.RoomElementRepository
import org.example.project.features.table.domain.service.RoomElementService
import org.example.project.features.table.mapper.toResponse
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.log.EntryLogConfig
import org.example.project.utils.log.EntryLogData
import org.slf4j.LoggerFactory

class RoomElementServiceImpl(
    private val roomElementRepository: RoomElementRepository,
) : RoomElementService {
    private val logger = LoggerFactory.getLogger(RoomElementServiceImpl::class.java)

    override suspend fun getAllElements(
        limit: Int?,
        offset: Int?,
        elementType: RoomElementType?,
        locationTypeId: Long?,
    ): RoomElementDto.RoomElementListResponseDto =
        dbQuery {
            val elements =
                roomElementRepository
                    .getAllElements(limit, offset, elementType, locationTypeId)
                    .map { it.toResponse() }
            logger.debug(
                "getAllElements: returned {} room elements (type={}, locationTypeId={})",
                elements.size,
                elementType,
                locationTypeId,
            )
            RoomElementDto.RoomElementListResponseDto(
                elements = elements,
                totalCount = elements.size,
                returnedCount = elements.size,
                appliedFilters =
                    RoomElementDto.FilterInfo(
                        elementType = elementType?.name,
                        locationType = locationTypeId?.toString(),
                        limit = limit,
                        offset = offset,
                    ),
            )
        }

    override suspend fun getElementById(id: Long): RoomElementDto.RoomElementResponseDto =
        dbQuery {
            val element = roomElementRepository.getElementById(id) ?: throw roomElementNotFound(id)
            element.toResponse()
        }

    override suspend fun createElement(
        employeeId: String,
        request: RoomElementDto.CreateRoomElementRequestDto,
    ): RoomElementDto.RoomElementResponseDto =
        ExceptionConverter.catchingThrows(
            context = "createElement(employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.ROOM_ELEMENT,
                    logOperation = LogOperation.CREATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.id.toString(), dto.elementType, "Created room element '${dto.elementType}'")
                    },
                ),
        ) {
            dbQuery {
                val element = roomElementRepository.createElement(request)
                logger.info(
                    "createElement: created room element type={} at ({}, {})",
                    request.elementType,
                    request.positionX,
                    request.positionY,
                )
                val response = element.toResponse()
                NotificationService.notifyDataChange(
                    ChangeEntityType.ROOM_ELEMENT,
                    DataChangeOperation.CREATED,
                    element.id.value,
                    excludeEmployeeId = employeeId,
                )
                response
            }
        }

    override suspend fun updateElement(
        employeeId: String,
        id: Long,
        request: RoomElementDto.UpdateRoomElementRequestDto,
    ): RoomElementDto.RoomElementResponseDto {
        if (!request.hasChanges()) {
            logger.debug("updateElement: no changes provided for room element id={}", id)
            return getElementById(id)
        }
        return ExceptionConverter.catchingThrows(
            context = "updateElement(id=$id, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.ROOM_ELEMENT,
                    logOperation = LogOperation.UPDATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.id.toString(), dto.elementType, "Updated room element '${dto.elementType}'")
                    },
                ),
        ) {
            dbQuery {
                val element =
                    roomElementRepository.updateElement(id, request)
                        ?: throw roomElementNotFound(id)
                logger.info("updateElement: updated room element id={}", id)
                val response = element.toResponse()
                NotificationService.notifyDataChange(
                    ChangeEntityType.ROOM_ELEMENT,
                    DataChangeOperation.UPDATED,
                    id,
                    excludeEmployeeId = employeeId,
                )
                response
            }
        }
    }

    override suspend fun updateElementPosition(
        id: Long,
        request: RoomElementDto.UpdateRoomElementPositionRequestDto,
    ): RoomElementDto.RoomElementResponseDto =
        dbQuery {
            val element =
                roomElementRepository.updateElementPosition(id, request)
                    ?: throw roomElementNotFound(id)
            logger.info(
                "updateElementPosition: updated room element id={} to ({}, {})",
                id,
                request.positionX,
                request.positionY,
            )
            NotificationService.notifyDataChange(
                ChangeEntityType.ROOM_ELEMENT,
                DataChangeOperation.UPDATED,
                id,
            )
            element.toResponse()
        }

    override suspend fun updateElementDimensions(
        id: Long,
        request: RoomElementDto.UpdateRoomElementDimensionsRequestDto,
    ): RoomElementDto.RoomElementResponseDto =
        dbQuery {
            val element =
                roomElementRepository.updateElementDimensions(id, request)
                    ?: throw roomElementNotFound(id)
            logger.info(
                "updateElementDimensions: updated room element id={} to {}x{}",
                id,
                request.width,
                request.height,
            )
            NotificationService.notifyDataChange(
                ChangeEntityType.ROOM_ELEMENT,
                DataChangeOperation.UPDATED,
                id,
            )
            element.toResponse()
        }

    override suspend fun deleteElement(
        employeeId: String,
        id: Long,
    ) {
        ExceptionConverter.catchingThrows(
            context = "deleteElement(id=$id, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.ROOM_ELEMENT,
                    logOperation = LogOperation.DELETE,
                    extractLogData = { _: Unit ->
                        EntryLogData(id.toString(), "Room element", "Deleted room element #$id")
                    },
                ),
        ) {
            dbQuery {
                try {
                    roomElementRepository.deleteElement(id)
                } catch (e: NoSuchElementException) {
                    throw roomElementNotFound(id)
                }
                logger.info("deleteElement: deleted room element id={}", id)
                NotificationService.notifyDataChange(
                    ChangeEntityType.ROOM_ELEMENT,
                    DataChangeOperation.DELETED,
                    id,
                    excludeEmployeeId = employeeId,
                )
            }
        }
    }

    override suspend fun getElementsByType(elementType: RoomElementType): List<RoomElementDto.RoomElementResponseDto> =
        dbQuery {
            val elements = roomElementRepository.getElementsByType(elementType).map { it.toResponse() }
            logger.debug("getElementsByType: found {} elements with type={}", elements.size, elementType.name)
            elements
        }

    override suspend fun getElementsByLocationTypeId(locationTypeId: Long): List<RoomElementDto.RoomElementResponseDto> =
        dbQuery {
            val elements =
                roomElementRepository
                    .getElementsByLocationTypeId(locationTypeId)
                    .map { it.toResponse() }
            logger.debug(
                "getElementsByLocationTypeId: found {} elements for locationTypeId={}",
                elements.size,
                locationTypeId,
            )
            elements
        }

    override suspend fun getElementsUpdatedAfter(lastSyncTime: String): List<RoomElementDto.RoomElementResponseDto> {
        val parsedDateTime =
            try {
                LocalDateTime.parse(lastSyncTime)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("lastSyncTime must be ISO datetime (e.g., 2024-12-01T10:30:00)")
            }
        return dbQuery {
            val elements =
                roomElementRepository
                    .getElementsUpdatedAfter(parsedDateTime)
                    .map { it.toResponse() }
            logger.debug(
                "getElementsUpdatedAfter: returned {} elements updated after {}",
                elements.size,
                lastSyncTime,
            )
            elements
        }
    }

    private fun roomElementNotFound(id: Long) =
        PosException(
            PosErrorCode.ROOM_ELEMENT_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Room element $id not found",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
