package org.example.project.admin.features.supplier

import org.example.project.core.data.remote.SupplierApiClient
import org.example.project.core.domain.dto.supplier.SupplierDto

/**
 * Records calls and replays canned answers. [listCalls] exists so a test can prove the ViewModel
 * did NOT re-request — the filter tests depend on that.
 */
class FakeSupplierApiClient : SupplierApiClient {
    var suppliers: List<SupplierDto.ResponseDto> = emptyList()
    var total: Long = 0
    var failWith: Throwable? = null

    val listCalls = mutableListOf<Triple<Boolean, Int, Long>>()
    val updates = mutableListOf<Pair<Long, SupplierDto.UpdateRequestDto>>()

    /** What the last request narrowed and ordered by. Search and sort are server-side, so the state
     * alone cannot prove the ViewModel sent them. */
    var lastSearch: String? = null
    var lastSort: String = "NAME"
    var lastAscending: Boolean = true

    override suspend fun list(
        provisionalOnly: Boolean,
        search: String?,
        sort: String,
        ascending: Boolean,
        limit: Int,
        offset: Long,
    ): SupplierDto.ListResponseDto {
        listCalls += Triple(provisionalOnly, limit, offset)
        lastSearch = search
        lastSort = sort
        lastAscending = ascending
        failWith?.let { throw it }
        val page = if (provisionalOnly) suppliers.filter { it.isProvisional } else suppliers
        return SupplierDto.ListResponseDto(items = page, total = total)
    }

    override suspend fun get(id: Long): SupplierDto.ResponseDto {
        failWith?.let { throw it }
        return suppliers.first { it.id == id }
    }

    /** Every create request, so a test can assert the fields a dialog collected actually went out. */
    val creates = mutableListOf<SupplierDto.CreateRequestDto>()

    /** What create answers with. Null echoes the request, which is enough for most callers. */
    var createResponse: SupplierDto.ResponseDto? = null

    override suspend fun create(request: SupplierDto.CreateRequestDto): SupplierDto.ResponseDto {
        creates += request
        failWith?.let { throw it }
        return createResponse ?: SupplierDto.ResponseDto(id = 1, name = request.name, vatId = request.vatId)
    }

    override suspend fun update(
        id: Long,
        request: SupplierDto.UpdateRequestDto,
    ): SupplierDto.ResponseDto {
        updates += id to request
        failWith?.let { throw it }
        return SupplierDto.ResponseDto(id = id, name = request.name, vatId = request.vatId)
    }
}
