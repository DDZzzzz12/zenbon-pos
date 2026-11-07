package org.example.project.core.data.remote

import org.example.project.core.domain.dto.supplier.SupplierDto

/**
 * Creditor master data. Every endpoint is MANAGER-only server-side.
 *
 * Lives in `shared` rather than `adminApp` so composeApp can reuse it if the POS ever needs to show
 * a supplier; nothing outside adminApp calls it today.
 */
interface SupplierApiClient {
    /**
     * [provisionalOnly] narrows to suppliers created without a VAT ID, awaiting confirmation.
     * [search] matches name, VAT ID or creditor number server-side; a blank term is not a filter.
     * [sort] is one of `NAME`, `VAT_ID`, `CREDITOR_NO`, `STATUS` — anything else falls back to
     * `NAME` rather than failing. It is a `String` and not the server's `SupplierSort` because that
     * enum lives in `core-data`, which `shared` does not depend on and must not start depending on.
     */
    suspend fun list(
        provisionalOnly: Boolean = false,
        search: String? = null,
        sort: String = "NAME",
        ascending: Boolean = true,
        limit: Int = 200,
        offset: Long = 0,
    ): SupplierDto.ListResponseDto

    suspend fun get(id: Long): SupplierDto.ResponseDto

    suspend fun create(request: SupplierDto.CreateRequestDto): SupplierDto.ResponseDto

    /** A full replace. Clears the supplier's provisional flag server-side. */
    suspend fun update(
        id: Long,
        request: SupplierDto.UpdateRequestDto,
    ): SupplierDto.ResponseDto
}
