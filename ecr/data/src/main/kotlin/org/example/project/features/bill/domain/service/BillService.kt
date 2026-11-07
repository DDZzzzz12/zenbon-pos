package org.example.project.features.bill.domain.service

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.auth.Capability
import java.math.BigDecimal

/**
 * Service interface for bill management operations.
 *
 * Provides business logic for managing bills including CRUD operations,
 * bill search, payment tracking, and business reporting. Returns DTOs
 * for API consumption and handles business validation.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts to application/problem+json.
 * Migrated from Result<T, Error> in Stage B Batch 2.
 */
interface BillService {
    suspend fun getAllBills(includeDeleted: Boolean = false): List<BillDto.BillResponseDto>

    suspend fun getBillById(billId: String): BillDto.BillResponseDto

    suspend fun getBillByNumber(billNumber: String): BillDto.BillResponseDto

    suspend fun getBillByTseTransactionNumber(transactionNumber: Long): BillDto.BillResponseDto

    suspend fun getBillsByEmployeeId(
        employeeId: String,
        includeDeleted: Boolean = false,
    ): List<BillDto.BillResponseDto>

    suspend fun getBillsByTableSessionId(
        tableSessionId: Long,
        includeDeleted: Boolean = false,
    ): List<BillDto.BillResponseDto>

    suspend fun getBillsByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        includeDeleted: Boolean = false,
    ): List<BillDto.BillResponseDto>

    suspend fun getBillsByAmountRange(
        minAmount: BigDecimal,
        maxAmount: BigDecimal,
        includeDeleted: Boolean = false,
    ): List<BillDto.BillResponseDto>

    suspend fun createBill(
        employeeId: String,
        deviceId: String,
        request: BillDto.CreateBillRequestDto,
    ): BillDto.BillResponseDto

    /**
     * Performs a full bill cancellation (Storno / Belegstorno) per DSFinV-K. See implementation
     * docstring for full workflow details.
     *
     * [restoreSession] re-opens the bill's completed table session and copies its items back for
     * re-billing (the default). `false` is a plain Storno that leaves session and table alone. A
     * restore is refused (409 `TABLE_HAS_ACTIVE_SESSION`) when new guests now occupy the table.
     */
    suspend fun deleteBill(
        employeeId: String,
        deviceId: String,
        billId: String,
        deletingReason: String? = null,
        restoreSession: Boolean = true,
    )

    /**
     * Zahlartwechsel: stornos [billId] and books its items again as a new bill with the requested
     * tender and tip, under the original seller. Returns the new bill. [actorCapabilities] is what the
     * route resolved the caller to hold: lowering the tip needs `BILL_VOID` among them.
     */
    suspend fun rebookBill(
        employeeId: String,
        deviceId: String,
        billId: String,
        request: BillDto.RebookRequestDto,
        actorCapabilities: Set<Capability>,
    ): BillDto.BillResponseDto

    suspend fun getBillSummaries(
        offset: Int,
        limit: Int,
        includeDeleted: Boolean = false,
    ): List<BillDto.BillSummaryDto>

    suspend fun searchBills(
        employeeId: String? = null,
        tableSessionId: Long? = null,
        startDate: LocalDateTime? = null,
        endDate: LocalDateTime? = null,
        minAmount: BigDecimal? = null,
        maxAmount: BigDecimal? = null,
        tipRecipient: String? = null,
        includeDeleted: Boolean = false,
        offset: Int = 0,
        limit: Int = 50,
        includeTraining: Boolean = false,
    ): List<BillDto.BillResponseDto>

    suspend fun getBillStatistics(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        includeDeleted: Boolean = false,
    ): BillDto.BillStatisticsDto

    suspend fun printBill(
        billId: String,
        employeeId: String,
        bewirtungsbeleg: Boolean = false,
    )

    suspend fun getTseQrCodeData(billId: String): BillDto.TseQrCodeDataDto?
}
