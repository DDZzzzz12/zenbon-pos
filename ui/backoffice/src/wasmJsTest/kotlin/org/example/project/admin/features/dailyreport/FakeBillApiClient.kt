package org.example.project.admin.features.dailyreport

import kotlinx.coroutines.delay
import org.example.project.core.data.remote.BillApiClient
import org.example.project.core.domain.dto.bill.BillDto

/**
 * Hand-written [BillApiClient] fake; [getBillById], [search] and [getBillsByTableSessionId] are
 * implemented.
 *
 * The daily report needs [getBillById]: its rows carry a [BillDto.BillSummaryDto], and opening one
 * has to fetch the full bill. Set [failure] to make that fetch throw, or [delayMs] to hold the call
 * open so the in-flight state can be observed. The Z-Bon hub pages through [search] over
 * [searchResults], hiding deleted bills and training receipts unless asked, exactly as the server does; the session detail
 * reads [sessionBills] the same way.
 */
class FakeBillApiClient(
    var bill: BillDto.BillResponseDto? = null,
    var failure: Exception? = null,
    var delayMs: Long = 0,
    /** Per-id answers, for following related-bill links; falls back to [bill]. */
    var billsById: Map<Long, BillDto.BillResponseDto> = emptyMap(),
    /** Every bill [search] can return, newest first, deleted ones included. */
    var searchResults: List<BillDto.BillResponseDto> = emptyList(),
    /** Every bill of the session [getBillsByTableSessionId] answers with, deleted ones included. */
    var sessionBills: List<BillDto.BillResponseDto> = emptyList(),
) : BillApiClient {
    /** Ids passed to [getBillById], in call order. */
    val requestedIds = mutableListOf<Long>()

    /** (includeDeleted, offset, limit) of every [search] call, in call order. */
    val searchCalls = mutableListOf<Triple<Boolean, Int, Int>>()

    /** includeTraining of every [search] call, in call order. */
    val searchTrainingFlags = mutableListOf<Boolean>()

    /** includeDeleted of every [getBillsByTableSessionId] call, in call order. */
    val sessionCalls = mutableListOf<Boolean>()

    /** What the server's `deletingReason IS NULL` filter keeps when includeDeleted is false. */
    private fun List<BillDto.BillResponseDto>.visible(includeDeleted: Boolean) = if (includeDeleted) this else filter { it.deletingReason == null }

    override suspend fun getBillById(billId: Long): BillDto.BillResponseDto {
        requestedIds += billId
        if (delayMs > 0) delay(delayMs)
        failure?.let { throw it }
        return billsById[billId] ?: bill ?: throw IllegalStateException("FakeBillApiClient: no bill configured")
    }

    override suspend fun getBillByNumber(billNumber: String) = throw NotImplementedError()

    override suspend fun getBillByTseTransactionNumber(transactionNumber: Long) = throw NotImplementedError()

    override suspend fun createBill(request: BillDto.CreateBillRequestDto) = throw NotImplementedError()

    override suspend fun getBillsByTableSessionId(
        tableSessionId: Long,
        includeDeleted: Boolean,
    ): List<BillDto.BillResponseDto> {
        sessionCalls += includeDeleted
        return sessionBills.visible(includeDeleted)
    }

    override suspend fun deleteBill(
        billId: Long,
        deletingReason: String?,
        restoreSession: Boolean,
    ) = throw NotImplementedError()

    override suspend fun rebookBill(
        billId: Long,
        request: BillDto.RebookRequestDto,
    ): BillDto.BillResponseDto = throw NotImplementedError()

    override suspend fun printBill(
        billId: Long,
        bewirtungsbeleg: Boolean,
    ) = throw NotImplementedError()

    override suspend fun getAllBills(includeDeleted: Boolean) = throw NotImplementedError()

    override suspend fun search(
        employeeNumber: String?,
        tableSessionId: Long?,
        startDate: String?,
        endDate: String?,
        minAmount: Double?,
        maxAmount: Double?,
        tipRecipient: String?,
        includeDeleted: Boolean,
        offset: Int,
        limit: Int,
        includeTraining: Boolean,
    ): List<BillDto.BillResponseDto> {
        searchCalls += Triple(includeDeleted, offset, limit)
        searchTrainingFlags += includeTraining
        // The server's TrainingFilter hides Schulungsbelege unless asked.
        return searchResults
            .visible(includeDeleted)
            .filter { includeTraining || !it.isTraining }
            .drop(offset)
            .take(limit)
    }

    override suspend fun getSummaries(
        offset: Int,
        limit: Int,
        includeDeleted: Boolean,
    ) = throw NotImplementedError()

    override suspend fun getStatistics(
        startDate: String?,
        endDate: String?,
        includeDeleted: Boolean,
    ) = throw NotImplementedError()
}
