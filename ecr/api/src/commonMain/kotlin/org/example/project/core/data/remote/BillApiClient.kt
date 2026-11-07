/**
 * API client interface for bill operations.
 *
 * Throws [org.example.project.core.network.error.PosApiException] /
 * [org.example.project.core.network.error.NetworkApiException] on failure. Wrap
 * call sites in `runCatchingApi { ... }.onError { _error.value = it }` for VM
 * state handling.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.bill.BillDto

interface BillApiClient {
    suspend fun getBillById(billId: Long): BillDto.BillResponseDto

    suspend fun getBillByNumber(billNumber: String): BillDto.BillResponseDto

    suspend fun getBillByTseTransactionNumber(transactionNumber: Long): BillDto.BillResponseDto

    suspend fun createBill(request: BillDto.CreateBillRequestDto): BillDto.BillResponseDto

    suspend fun getBillsByTableSessionId(
        tableSessionId: Long,
        includeDeleted: Boolean = false,
    ): List<BillDto.BillResponseDto>

    /**
     * Storno. [restoreSession] re-opens the table session for re-billing; the server refuses it with
     * 409 `TABLE_HAS_ACTIVE_SESSION` when new guests now occupy the table.
     */
    suspend fun deleteBill(
        billId: Long,
        deletingReason: String? = null,
        restoreSession: Boolean = true,
    ): String

    /**
     * Zahlartwechsel: storno the bill and book it again with the request's tender and whole tip.
     * Returns the NEW bill (`relations.kind == REBOOKING`, `relations.rebooksBill` = the original).
     */
    suspend fun rebookBill(
        billId: Long,
        request: BillDto.RebookRequestDto,
    ): BillDto.BillResponseDto

    suspend fun printBill(
        billId: Long,
        bewirtungsbeleg: Boolean = false,
    )

    suspend fun getAllBills(includeDeleted: Boolean = false): List<BillDto.BillResponseDto>

    suspend fun search(
        employeeNumber: String? = null,
        tableSessionId: Long? = null,
        startDate: String? = null,
        endDate: String? = null,
        minAmount: Double? = null,
        maxAmount: Double? = null,
        tipRecipient: String? = null,
        includeDeleted: Boolean = false,
        offset: Int = 0,
        limit: Int = 50,
        /** Adds Schulungsbelege (badged via `isTraining`); only a receipt LIST asks for them. */
        includeTraining: Boolean = false,
    ): List<BillDto.BillResponseDto>

    suspend fun getSummaries(
        offset: Int = 0,
        limit: Int = 50,
        includeDeleted: Boolean = false,
    ): List<BillDto.BillSummaryDto>

    suspend fun getStatistics(
        startDate: String? = null,
        endDate: String? = null,
        includeDeleted: Boolean = false,
    ): BillDto.BillStatisticsDto
}
