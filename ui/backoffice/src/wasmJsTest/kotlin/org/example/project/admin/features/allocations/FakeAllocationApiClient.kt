package org.example.project.admin.features.allocations

import org.example.project.core.data.remote.AllocationApiClient
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.domain.dto.allocation.SuggestionDto

/** Records calls and replays canned answers; the call lists prove the screen re-reads after a write. */
class FakeAllocationApiClient : AllocationApiClient {
    var rowsByFilter: Map<AllocationDto.QueueFilter, List<AllocationDto.QueueRowDto>> = emptyMap()
    var invoices: List<AllocationDto.OpenInvoiceDto> = emptyList()
    var salesInvoices: List<AllocationDto.OpenSalesInvoiceDto> = emptyList()
    var instalments: List<AllocationDto.OpenInstalmentDto> = emptyList()

    /** Canned suggestions per transaction; a transaction with no entry answers with an empty list. */
    var suggestionsByTransaction: Map<Long, List<SuggestionDto.ItemDto>> = emptyMap()
    var failWith: Throwable? = null

    val queueCalls = mutableListOf<AllocationDto.QueueFilter>()
    val openInvoiceCalls = mutableListOf<Long?>()
    val openSalesInvoiceCalls = mutableListOf<Long?>()

    /** One entry per `openInstalments` call, holding its offset — there is no supplier to record. */
    val openInstalmentCalls = mutableListOf<Long>()
    val suggestionCalls = mutableListOf<Long>()

    /** Single writes only. A batch is never flattened in here — see [createdBatches]. */
    val created = mutableListOf<AllocationDto.CreateRequestDto>()

    /**
     * One entry per `allocateAll`, holding the whole list. Recorded as batches rather than flattened
     * so a test can prove a three-invoice suggestion went out as **one** call, not three.
     */
    val createdBatches = mutableListOf<List<AllocationDto.CreateRequestDto>>()
    val reversed = mutableListOf<Long>()

    private var nextId = 0L

    override suspend fun queue(
        filter: AllocationDto.QueueFilter,
        limit: Int,
        offset: Long,
    ): AllocationDto.QueueResponseDto {
        queueCalls += filter
        failWith?.let { throw it }
        val items = rowsByFilter[filter].orEmpty()
        return AllocationDto.QueueResponseDto(items = items, total = items.size.toLong())
    }

    override suspend fun openInvoices(
        supplierId: Long?,
        limit: Int,
        offset: Long,
    ): AllocationDto.OpenInvoiceListDto {
        openInvoiceCalls += supplierId
        failWith?.let { throw it }
        return AllocationDto.OpenInvoiceListDto(items = invoices, total = invoices.size.toLong())
    }

    override suspend fun openSalesInvoices(
        debtorId: Long?,
        limit: Int,
        offset: Long,
    ): AllocationDto.OpenSalesInvoiceListDto {
        openSalesInvoiceCalls += debtorId
        failWith?.let { throw it }
        return AllocationDto.OpenSalesInvoiceListDto(items = salesInvoices, total = salesInvoices.size.toLong())
    }

    override suspend fun openInstalments(
        limit: Int,
        offset: Long,
    ): AllocationDto.OpenInstalmentListDto {
        openInstalmentCalls += offset
        failWith?.let { throw it }
        return AllocationDto.OpenInstalmentListDto(items = instalments, total = instalments.size.toLong())
    }

    override suspend fun suggestions(transactionId: Long): SuggestionDto.ResponseDto {
        suggestionCalls += transactionId
        failWith?.let { throw it }
        val items = suggestionsByTransaction[transactionId].orEmpty()
        return SuggestionDto.ResponseDto(items = items, total = items.size)
    }

    override suspend fun allocate(request: AllocationDto.CreateRequestDto): AllocationDto.CreateResponseDto {
        created += request
        failWith?.let { throw it }
        return written(request)
    }

    override suspend fun allocateAll(requests: List<AllocationDto.CreateRequestDto>): List<AllocationDto.CreateResponseDto> {
        createdBatches += requests
        failWith?.let { throw it }
        return requests.map { written(it) }
    }

    /** Echoes the request back, so a test reads the provenance the caller actually sent. */
    private fun written(request: AllocationDto.CreateRequestDto): AllocationDto.CreateResponseDto =
        AllocationDto.CreateResponseDto(
            allocation =
                AllocationDto.AllocationRowDto(
                    id = ++nextId,
                    sourceKind = request.sourceKind,
                    targetKind = request.targetKind,
                    amount = request.amount,
                    matchedBy = request.matchedBy,
                    invoiceId = request.invoiceId,
                    contractInstalmentId = request.contractInstalmentId,
                ),
        )

    override suspend fun reverse(allocationId: Long) {
        reversed += allocationId
        failWith?.let { throw it }
    }
}
