package org.example.project.features.timetracking.domain.service

import org.example.project.core.domain.dto.timetracking.CostCenterDto

/**
 * Service interface for CostCenter business operations.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * StatusPages catches it and emits application/problem+json.
 */
interface CostCenterService {
    suspend fun getAll(includeInactive: Boolean = false): CostCenterDto.CostCenterListResponseDto

    suspend fun getById(id: String): CostCenterDto.CostCenterResponseDto

    suspend fun create(request: CostCenterDto.CreateCostCenterRequestDto): CostCenterDto.CostCenterResponseDto

    suspend fun update(
        id: String,
        request: CostCenterDto.UpdateCostCenterRequestDto,
    ): CostCenterDto.CostCenterResponseDto

    suspend fun delete(id: String)
}
