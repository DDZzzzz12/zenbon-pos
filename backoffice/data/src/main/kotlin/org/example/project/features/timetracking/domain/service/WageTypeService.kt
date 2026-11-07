package org.example.project.features.timetracking.domain.service

import org.example.project.core.domain.dto.timetracking.WageTypeDto

/**
 * Service interface for WageType business operations.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * StatusPages catches it and emits application/problem+json.
 */
interface WageTypeService {
    suspend fun getAll(): WageTypeDto.WageTypeListResponseDto

    suspend fun getById(id: String): WageTypeDto.WageTypeResponseDto

    suspend fun create(request: WageTypeDto.CreateWageTypeRequestDto): WageTypeDto.WageTypeResponseDto

    suspend fun update(
        id: String,
        request: WageTypeDto.UpdateWageTypeRequestDto,
    ): WageTypeDto.WageTypeResponseDto

    suspend fun delete(id: String)
}
