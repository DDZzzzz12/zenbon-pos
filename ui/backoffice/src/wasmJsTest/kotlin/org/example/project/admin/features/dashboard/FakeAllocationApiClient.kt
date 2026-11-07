package org.example.project.admin.features.dashboard

import org.example.project.core.data.remote.AllocationApiClient
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.domain.dto.allocation.SuggestionDto

class FakeAllocationApiClient(
    private val queueResponse: () -> AllocationDto.QueueResponseDto,
) : AllocationApiClient {
    var lastFilter: AllocationDto.QueueFilter? = null

    override suspend fun queue(
        filter: AllocationDto.QueueFilter,
        limit: Int,
        offset: Long,
    ): AllocationDto.QueueResponseDto {
        lastFilter = filter
        return queueResponse()
    }

    override suspend fun openInvoices(
        supplierId: Long?,
        limit: Int,
        offset: Long,
    ): AllocationDto.OpenInvoiceListDto = throw NotImplementedError()

    override suspend fun openSalesInvoices(
        debtorId: Long?,
        limit: Int,
        offset: Long,
    ): AllocationDto.OpenSalesInvoiceListDto = throw NotImplementedError()

    override suspend fun openInstalments(
        limit: Int,
        offset: Long,
    ): AllocationDto.OpenInstalmentListDto = throw NotImplementedError()

    override suspend fun suggestions(transactionId: Long): SuggestionDto.ResponseDto = throw NotImplementedError()

    override suspend fun allocate(request: AllocationDto.CreateRequestDto): AllocationDto.CreateResponseDto = throw NotImplementedError()

    override suspend fun allocateAll(requests: List<AllocationDto.CreateRequestDto>): List<AllocationDto.CreateResponseDto> = throw NotImplementedError()

    override suspend fun reverse(allocationId: Long): Unit = throw NotImplementedError()
}
