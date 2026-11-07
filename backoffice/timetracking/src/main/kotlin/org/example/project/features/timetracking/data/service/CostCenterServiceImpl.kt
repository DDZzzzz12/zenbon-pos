package org.example.project.features.timetracking.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.timetracking.CostCenterDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.timetracking.domain.repository.CostCenterRepository
import org.example.project.features.timetracking.domain.service.CostCenterService
import org.example.project.features.timetracking.mapper.toListResponse
import org.example.project.features.timetracking.mapper.toResponse
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory
import java.util.UUID

class CostCenterServiceImpl(
    private val costCenterRepository: CostCenterRepository,
) : CostCenterService {
    private val logger = LoggerFactory.getLogger(CostCenterServiceImpl::class.java)

    override suspend fun getAll(includeInactive: Boolean): CostCenterDto.CostCenterListResponseDto =
        dbQuery {
            costCenterRepository.findAll(includeInactive).toListResponse()
        }

    override suspend fun getById(id: String): CostCenterDto.CostCenterResponseDto =
        dbQuery {
            val costCenter =
                costCenterRepository.findById(UUID.fromString(id))
                    ?: throw notFound()
            costCenter.toResponse()
        }

    override suspend fun create(request: CostCenterDto.CreateCostCenterRequestDto): CostCenterDto.CostCenterResponseDto {
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid request")
        }
        return dbQuery {
            val existing = costCenterRepository.findByCode(request.code)
            if (existing != null) {
                throw PosException(
                    PosErrorCode.INVALID_REQUEST,
                    HttpStatusCode.Conflict,
                    "Cost center code '${request.code}' already exists",
                )
            }

            val costCenter = costCenterRepository.create(request.code, request.name, request.isActive)
            costCenter.toResponse()
        }
    }

    override suspend fun update(
        id: String,
        request: CostCenterDto.UpdateCostCenterRequestDto,
    ): CostCenterDto.CostCenterResponseDto =
        dbQuery {
            val updated =
                costCenterRepository.update(UUID.fromString(id), request.code, request.name, request.isActive)
                    ?: throw notFound()
            updated.toResponse()
        }

    override suspend fun delete(id: String) {
        dbQuery {
            val deleted = costCenterRepository.delete(UUID.fromString(id))
            if (!deleted) throw notFound()
        }
    }

    private fun notFound() =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Cost center not found",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
