package org.example.project.features.pfand.domain.service

import org.example.project.core.domain.dto.pfand.PfandTypeDto

/** Business operations for the Pfand (deposit) type catalog. */
interface PfandTypeService {
    suspend fun list(includeInactive: Boolean = false): List<PfandTypeDto.PfandTypeResponseDto>

    suspend fun create(request: PfandTypeDto.CreatePfandTypeRequestDto): PfandTypeDto.PfandTypeResponseDto

    suspend fun update(
        id: Long,
        request: PfandTypeDto.UpdatePfandTypeRequestDto,
    ): PfandTypeDto.PfandTypeResponseDto

    suspend fun deactivate(id: Long)
}
