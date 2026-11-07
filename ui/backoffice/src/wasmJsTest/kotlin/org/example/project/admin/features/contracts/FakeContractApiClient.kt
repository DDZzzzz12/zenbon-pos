package org.example.project.admin.features.contracts

import org.example.project.core.data.remote.ContractApiClient
import org.example.project.core.domain.dto.contract.ContractAlertDto
import org.example.project.core.domain.dto.contract.ContractDto

/**
 * Records calls and replays canned answers.
 *
 * [listCalls] carries the `includeInactive` flag every request was made with, which is what proves
 * the toggle re-requests instead of narrowing the loaded rows — the server owns that predicate.
 */
class FakeContractApiClient : ContractApiClient {
    var contracts: List<ContractDto.ResponseDto> = emptyList()
    var total: Long = 0
    var instalmentsByContract: Map<Long, List<ContractDto.InstalmentDto>> = emptyMap()
    var alerts: List<ContractAlertDto.AlertDto> = emptyList()
    var failWith: Throwable? = null

    /** Fails only [alerts], leaving [list] answering — the case where the table must survive. */
    var alertsFailWith: Throwable? = null

    val listCalls = mutableListOf<Boolean>()
    val getCalls = mutableListOf<Long>()
    val instalmentCalls = mutableListOf<Long>()
    var alertCalls = 0
        private set
    val created = mutableListOf<ContractDto.CreateRequestDto>()
    val updated = mutableListOf<Pair<Long, ContractDto.UpdateRequestDto>>()

    /** What the last request narrowed by. Search is server-side, so state alone cannot prove it was sent. */
    var lastSearch: String? = null

    override suspend fun list(
        includeInactive: Boolean,
        search: String?,
    ): ContractDto.ListResponseDto {
        listCalls += includeInactive
        lastSearch = search
        failWith?.let { throw it }
        val items = if (includeInactive) contracts else contracts.filter { it.isActive }
        return ContractDto.ListResponseDto(items = items, total = total)
    }

    override suspend fun get(contractId: Long): ContractDto.ResponseDto {
        getCalls += contractId
        failWith?.let { throw it }
        return contracts.first { it.id == contractId }
    }

    override suspend fun create(request: ContractDto.CreateRequestDto): ContractDto.ResponseDto {
        created += request
        failWith?.let { throw it }
        return ContractDto.ResponseDto(
            id = created.size.toLong(),
            name = request.name,
            interval = request.interval,
            dueDay = request.dueDay,
            startDate = request.startDate,
            expectedAmount = request.expectedAmount,
        )
    }

    override suspend fun update(
        contractId: Long,
        request: ContractDto.UpdateRequestDto,
    ): ContractDto.ResponseDto {
        updated += contractId to request
        failWith?.let { throw it }
        return ContractDto.ResponseDto(
            id = contractId,
            name = request.name,
            interval = request.interval,
            dueDay = request.dueDay,
            startDate = request.startDate,
            expectedAmount = request.expectedAmount,
            isActive = request.isActive,
        )
    }

    override suspend fun alerts(): ContractAlertDto.ListResponseDto {
        alertCalls++
        alertsFailWith?.let { throw it }
        failWith?.let { throw it }
        return ContractAlertDto.ListResponseDto(items = alerts, total = alerts.size.toLong())
    }

    override suspend fun instalments(contractId: Long): ContractDto.InstalmentListDto {
        instalmentCalls += contractId
        failWith?.let { throw it }
        val items = instalmentsByContract[contractId].orEmpty()
        return ContractDto.InstalmentListDto(items = items, total = items.size.toLong())
    }
}
