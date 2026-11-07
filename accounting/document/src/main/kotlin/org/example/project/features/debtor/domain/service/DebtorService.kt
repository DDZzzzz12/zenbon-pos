package org.example.project.features.debtor.domain.service

import org.example.project.core.domain.dto.debtor.DebtorDto

/**
 * Debtor master data as the HTTP layer sees it: DTOs out, `PosException` on refusal.
 *
 * Mapping happens inside the service's own `dbQuery` — the entity's fields are lazy and unavailable
 * once the transaction closes.
 */
interface DebtorService {
    /**
     * A page of debtors. [search] is applied server-side, in the query, because the page boundary
     * would otherwise hide matches.
     */
    suspend fun list(
        activeOnly: Boolean,
        search: String?,
        limit: Int,
        offset: Long,
    ): DebtorDto.ListResponseDto

    suspend fun get(id: Long): DebtorDto.ResponseDto

    suspend fun create(request: DebtorDto.CreateRequestDto): DebtorDto.ResponseDto

    suspend fun update(
        id: Long,
        request: DebtorDto.UpdateRequestDto,
    ): DebtorDto.ResponseDto

    /**
     * Removes a debtor no invoice references.
     *
     * Refuses with `DEBTOR_IN_USE` otherwise: an issued invoice is kept ten years under §147 AO and
     * must keep pointing at the party it was issued to. Deactivating — `isActive = false` through
     * [update] — is the path for a debtor that has traded.
     */
    suspend fun delete(id: Long)
}
