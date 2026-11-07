package org.example.project.admin.features.debtors

import org.example.project.core.data.remote.DebtorApiClient
import org.example.project.core.domain.dto.debtor.DebtorDto

/**
 * Records what the ViewModel asked for, so a test can assert the *request* rather than only the
 * resulting state — the active filter is meant to be applied server-side, which is invisible from
 * the state alone.
 */
class FakeDebtorApiClient : DebtorApiClient {
    var debtors: List<DebtorDto.ResponseDto> = emptyList()
    var total: Long = 0
    var lastActiveOnly: Boolean? = null
    var listThrows: Throwable? = null
    var saveThrows: Throwable? = null
    var deleteThrows: Throwable? = null
    var deletedIds: MutableList<Long> = mutableListOf()
    var created: DebtorDto.CreateRequestDto? = null
    var updated: Pair<Long, DebtorDto.UpdateRequestDto>? = null

    /** What the last request narrowed by. Search is server-side, so state alone cannot prove it was sent. */
    var lastSearch: String? = null

    /** How many list requests were made — what proves the debounce collapsed a burst of keystrokes. */
    var listCalls: Int = 0

    override suspend fun list(
        activeOnly: Boolean,
        search: String?,
        limit: Int,
        offset: Long,
    ): DebtorDto.ListResponseDto {
        lastActiveOnly = activeOnly
        lastSearch = search
        listCalls++
        listThrows?.let { throw it }
        val items = if (activeOnly) debtors.filter { it.isActive } else debtors
        return DebtorDto.ListResponseDto(items = items, total = total)
    }

    override suspend fun get(id: Long): DebtorDto.ResponseDto = debtors.firstOrNull { it.id == id } ?: error("no fake debtor $id")

    override suspend fun create(request: DebtorDto.CreateRequestDto): DebtorDto.ResponseDto {
        created = request
        saveThrows?.let { throw it }
        return DebtorDto.ResponseDto(id = 99, name = request.name, debtorNo = request.debtorNo)
    }

    override suspend fun update(
        id: Long,
        request: DebtorDto.UpdateRequestDto,
    ): DebtorDto.ResponseDto {
        updated = id to request
        saveThrows?.let { throw it }
        return DebtorDto.ResponseDto(id = id, name = request.name, isActive = request.isActive)
    }

    override suspend fun delete(id: Long) {
        deleteThrows?.let { throw it }
        deletedIds += id
    }
}
