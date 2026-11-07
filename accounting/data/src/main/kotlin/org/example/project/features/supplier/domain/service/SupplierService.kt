package org.example.project.features.supplier.domain.service

import org.example.project.core.domain.dto.supplier.SupplierDto
import org.example.project.features.supplier.domain.repository.SupplierSort

/**
 * Creditor master data, as the HTTP layer sees it.
 *
 * Returns DTOs and throws `PosException` on error, per the server's service-layer contract. The
 * entity-to-DTO mapping happens inside the service's own `dbQuery`, because `knownIbans` reads a
 * lazily-loaded Exposed relation that is unavailable once the transaction closes.
 */
interface SupplierService {
    /**
     * A page of creditors. [search] and [sort] are applied server-side, in the query, because the
     * page boundary would otherwise hide matches and mis-order the list.
     */
    suspend fun list(
        provisionalOnly: Boolean,
        search: String?,
        sort: SupplierSort,
        ascending: Boolean,
        limit: Int,
        offset: Long,
    ): SupplierDto.ListResponseDto

    suspend fun get(id: Long): SupplierDto.ResponseDto

    suspend fun create(request: SupplierDto.CreateRequestDto): SupplierDto.ResponseDto

    suspend fun update(
        id: Long,
        request: SupplierDto.UpdateRequestDto,
    ): SupplierDto.ResponseDto
}
