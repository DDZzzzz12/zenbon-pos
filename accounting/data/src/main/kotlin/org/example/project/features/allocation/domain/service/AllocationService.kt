package org.example.project.features.allocation.domain.service

import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.domain.dto.matching.MatchTelemetryDto

/** Zuordnung: the queue, the candidates it can be settled against, and the writes. Throws `PosException`. */
interface AllocationService {
    suspend fun queue(
        filter: AllocationDto.QueueFilter,
        limit: Int,
        offset: Long,
    ): AllocationDto.QueueResponseDto

    suspend fun openInvoices(
        supplierId: Long?,
        limit: Int,
        offset: Long,
    ): AllocationDto.OpenInvoiceListDto

    /** Case-C (STANDALONE) invoices that still expect money, for the Zuordnung editor's AR panel. */
    suspend fun openSalesInvoices(
        debtorId: Long?,
        limit: Int,
        offset: Long,
    ): AllocationDto.OpenSalesInvoiceListDto

    /**
     * Contract instalments that still expect money, across every contract — earliest due first.
     *
     * The per-contract list cannot serve a picker: the operator has a payment in hand and does not
     * yet know which contract it belongs to. An instalment with no stated expectation is omitted,
     * and one written off as `WAIVED` is never a target.
     */
    suspend fun openInstalments(
        limit: Int,
        offset: Long,
    ): AllocationDto.OpenInstalmentListDto

    /** Validates, writes the allocation and both residuals atomically, and returns the new residuals. */
    suspend fun create(
        request: AllocationDto.CreateRequestDto,
        createdBy: String?,
    ): AllocationDto.CreateResponseDto

    /**
     * Writes a whole set of allocations in **one** transaction — all or none.
     *
     * A suggestion naming several invoices is one answer, not several: writing it as separate calls
     * can half-succeed, leaving a payment partly assigned against a set that no longer sums. Every
     * item is validated before any is written, so a rejection names the item the operator got wrong
     * rather than the state left behind. An empty list is refused.
     */
    suspend fun createAll(
        requests: List<AllocationDto.CreateRequestDto>,
        createdBy: String?,
    ): List<AllocationDto.CreateResponseDto>

    /**
     * Removes an allocation and restores both residuals. 404 when nothing had that id. The row is
     * hard-deleted, so the reversal is recorded as a `REVERSE_ALLOCATION` audit row naming
     * [reversedBy] and the removed assignment (T9-12).
     */
    suspend fun reverse(
        allocationId: Long,
        reversedBy: String?,
    )

    /**
     * How allocations came about, in counts — the evidence for judging which signals earn their
     * place. Two grouped queries over the whole table; there is no period filter yet because the
     * question being asked is about the ladder, not about a month.
     */
    suspend fun telemetry(): MatchTelemetryDto.ResponseDto
}
