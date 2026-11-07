package org.example.project.core.data.remote

import org.example.project.core.domain.dto.debtor.DebtorDto

/**
 * Debtor master data — the customers we invoice. Every endpoint is MANAGER-only server-side.
 *
 * Lives in `shared` beside [SupplierApiClient] for symmetry; only adminApp calls it, because the
 * till has no master-data surface.
 */
interface DebtorApiClient {
    /** [activeOnly] hides deactivated debtors. */
    /**
     * [search] matches name, city, VAT ID or debtor number server-side; a blank term is not a
     * filter, so an emptied box widens the list rather than clearing it.
     */
    suspend fun list(
        activeOnly: Boolean = false,
        search: String? = null,
        limit: Int = 200,
        offset: Long = 0,
    ): DebtorDto.ListResponseDto

    suspend fun get(id: Long): DebtorDto.ResponseDto

    suspend fun create(request: DebtorDto.CreateRequestDto): DebtorDto.ResponseDto

    /** A full replace — an omitted optional field is written as null. */
    suspend fun update(
        id: Long,
        request: DebtorDto.UpdateRequestDto,
    ): DebtorDto.ResponseDto

    /**
     * Only for a debtor no invoice references; the server answers 409 `DEBTOR_IN_USE` otherwise.
     * Retiring a debtor that has been invoiced is an [update] with `isActive = false`.
     */
    suspend fun delete(id: Long)
}
