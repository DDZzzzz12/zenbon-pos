package org.example.project.core.data.remote

import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.domain.dto.allocation.SuggestionDto

/**
 * Zuordnung — assigning payments to what they paid for. Every endpoint is MANAGER-only server-side.
 *
 * [openInvoices] takes a `supplierId` no caller passes: a queue row carries a counterparty name
 * and IBAN, not a resolved creditor. The matcher resolves suppliers for its own scoring but still
 * asks for every open invoice, so the parameter narrows nothing today.
 */
interface AllocationApiClient {
    suspend fun queue(
        filter: AllocationDto.QueueFilter,
        limit: Int = 100,
        offset: Long = 0,
    ): AllocationDto.QueueResponseDto

    suspend fun openInvoices(
        supplierId: Long? = null,
        limit: Int = 100,
        offset: Long = 0,
    ): AllocationDto.OpenInvoiceListDto

    /** Case-C (STANDALONE) invoices that still expect money — the AR panel of the editor. */
    suspend fun openSalesInvoices(
        debtorId: Long? = null,
        limit: Int = 100,
        offset: Long = 0,
    ): AllocationDto.OpenSalesInvoiceListDto

    /**
     * Contract instalments that still expect money, across every contract — the other kind of
     * target. There is no per-supplier narrowing: a contract is picked by what it is for.
     */
    suspend fun openInstalments(
        limit: Int = 100,
        offset: Long = 0,
    ): AllocationDto.OpenInstalmentListDto

    /** What the ladder thinks this payment settles. An empty list is a normal answer. */
    suspend fun suggestions(transactionId: Long): SuggestionDto.ResponseDto

    /** Assigns part or all of a payment. 409 when the amount exceeds what is unassigned. */
    suspend fun allocate(request: AllocationDto.CreateRequestDto): AllocationDto.CreateResponseDto

    /**
     * Writes several allocations in one transaction — all or none.
     *
     * This is how a suggestion naming several invoices is accepted: sending them one at a time can
     * half-succeed, leaving a payment partly assigned against a set that no longer sums.
     */
    suspend fun allocateAll(requests: List<AllocationDto.CreateRequestDto>): List<AllocationDto.CreateResponseDto>

    /** Removes an allocation, returning the payment to the queue and the invoice to the open list. */
    suspend fun reverse(allocationId: Long)
}
