package org.example.project.features.timetracking.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.timetracking.WageTypeDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.timetracking.domain.repository.WageTypeRepository
import org.example.project.features.timetracking.domain.service.WageTypeService
import org.example.project.features.timetracking.mapper.toListResponse
import org.example.project.features.timetracking.mapper.toResponse
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory
import java.util.UUID

class WageTypeServiceImpl(
    private val wageTypeRepository: WageTypeRepository,
) : WageTypeService {
    private val logger = LoggerFactory.getLogger(WageTypeServiceImpl::class.java)

    override suspend fun getAll(): WageTypeDto.WageTypeListResponseDto =
        dbQuery {
            wageTypeRepository.findAll().toListResponse()
        }

    override suspend fun getById(id: String): WageTypeDto.WageTypeResponseDto =
        dbQuery {
            val wageType =
                wageTypeRepository.findById(UUID.fromString(id))
                    ?: throw notFound()
            wageType.toResponse()
        }

    override suspend fun create(request: WageTypeDto.CreateWageTypeRequestDto): WageTypeDto.WageTypeResponseDto {
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid request")
        }
        return dbQuery {
            val existing = wageTypeRepository.findByCode(request.code)
            if (existing != null) {
                throw PosException(
                    PosErrorCode.INVALID_REQUEST,
                    HttpStatusCode.Conflict,
                    "Wage type code '${request.code}' already exists",
                )
            }

            val wageType = wageTypeRepository.create(request.code, request.name, request.description, request.isDefault)
            wageType.toResponse()
        }
    }

    override suspend fun update(
        id: String,
        request: WageTypeDto.UpdateWageTypeRequestDto,
    ): WageTypeDto.WageTypeResponseDto =
        dbQuery {
            val updated =
                wageTypeRepository.update(UUID.fromString(id), request.code, request.name, request.description, request.isDefault)
                    ?: throw notFound()
            updated.toResponse()
        }

    override suspend fun delete(id: String) {
        dbQuery {
            val deleted = wageTypeRepository.delete(UUID.fromString(id))
            if (!deleted) throw notFound()
        }
    }

    private fun notFound() =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Wage type not found",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
