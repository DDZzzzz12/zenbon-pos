/**
 * Implementation of LocationTypeService.
 *
 * Throws [PosException] on errors. Migrated from Result<T, Error> in Stage B Batch 10.
 */
package org.example.project.features.table.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.table.LocationTypeDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.table.domain.repository.LocationTypeRepository
import org.example.project.features.table.domain.service.LocationTypeService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.SystemRecordGuard
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory

class LocationTypeServiceImpl(
    private val locationTypeRepository: LocationTypeRepository,
) : LocationTypeService {
    private val logger = LoggerFactory.getLogger(LocationTypeServiceImpl::class.java)

    override suspend fun getAll(): LocationTypeDto.ListResponseDto =
        dbQuery {
            val locationTypes = locationTypeRepository.getAll().map { it.toDto() }
            logger.debug("getAll: returned {} location types", locationTypes.size)
            LocationTypeDto.ListResponseDto(locationTypes = locationTypes)
        }

    override suspend fun getById(id: Long): LocationTypeDto.ResponseDto =
        dbQuery {
            val entity = locationTypeRepository.getById(id) ?: throw locationTypeNotFound(id)
            entity.toDto()
        }

    override suspend fun create(request: LocationTypeDto.CreateRequestDto): LocationTypeDto.ResponseDto =
        dbQuery {
            val entity =
                locationTypeRepository.create(
                    name = request.name,
                    englishName = request.englishName,
                    chineseName = request.chineseName,
                    isVirtual = request.isVirtual,
                    isTakeaway = request.isTakeaway,
                    sortOrder = request.sortOrder,
                )
            logger.info("create: created location type name={}", request.name)
            entity.toDto()
        }

    override suspend fun update(
        id: Long,
        request: LocationTypeDto.UpdateRequestDto,
    ): LocationTypeDto.ResponseDto =
        dbQuery {
            requireNotSystem(id, "changed")
            val entity =
                locationTypeRepository.update(
                    id = id,
                    name = request.name,
                    englishName = request.englishName,
                    chineseName = request.chineseName,
                    isVirtual = request.isVirtual,
                    isTakeaway = request.isTakeaway,
                    sortOrder = request.sortOrder,
                ) ?: throw locationTypeNotFound(id)
            logger.info("update: updated location type id={}", id)
            entity.toDto()
        }

    override suspend fun delete(id: Long) {
        dbQuery {
            requireNotSystem(id, "deleted")
            val tableCount = locationTypeRepository.countTablesUsing(id)
            val elementCount = locationTypeRepository.countRoomElementsUsing(id)
            if (tableCount > 0 || elementCount > 0) {
                throw deleteHasReferences(
                    "Location type is in use by $tableCount tables and $elementCount room elements",
                )
            }
            if (!locationTypeRepository.delete(id)) {
                throw locationTypeNotFound(id)
            }
        }
        logger.info("delete: deleted location type id={}", id)
    }

    private fun org.example.project.features.table.domain.models.LocationType.toDto() =
        LocationTypeDto.ResponseDto(
            id = id.value,
            name = name,
            englishName = englishName,
            chineseName = chineseName,
            isVirtual = isVirtual,
            isTakeaway = isTakeaway,
            sortOrder = sortOrder,
            isSystem = isSystem,
        )

    private fun requireNotSystem(
        id: Long,
        action: String,
    ) {
        val existing = locationTypeRepository.getById(id) ?: throw locationTypeNotFound(id)
        if (existing.isSystem) {
            throw SystemRecordGuard.protectedRecord("Location type", existing.name, action)
        }
    }

    private fun locationTypeNotFound(id: Long) =
        PosException(
            PosErrorCode.LOCATION_TYPE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Location type $id not found",
        )

    private fun deleteHasReferences(msg: String) =
        PosException(
            PosErrorCode.DELETE_HAS_REFERENCES,
            HttpStatusCode.Conflict,
            msg,
        )
}
