package org.example.project.admin.features.product

import org.example.project.core.data.remote.PfandTypeApiClient
import org.example.project.core.domain.dto.pfand.PfandTypeDto

/** Replays [types]; throws [failWith] instead once it is set. */
class FakePfandTypeApiClient(
    var types: List<PfandTypeDto.PfandTypeResponseDto> = emptyList(),
) : PfandTypeApiClient {
    var failWith: Throwable? = null

    override suspend fun list(includeInactive: Boolean): List<PfandTypeDto.PfandTypeResponseDto> {
        failWith?.let { throw it }
        return types.filter { includeInactive || it.isActive }
    }
}
