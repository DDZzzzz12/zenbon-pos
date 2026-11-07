package org.example.project.features.bill.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.table.domain.models.TableSession
import java.math.BigDecimal

/**
 * Repository interface for Bill data access operations.
 *
 * Defines the contract for bill database operations including CRUD operations,
 * bill search, payment tracking, and business reporting. Returns plain entities
 * or nullable types; exceptions propagate naturally to the service layer.
 */
interface BillRepository {
    /**
     * Retrieves all bills from the database.
     *
     * @return List of all Bill entities, ordered by creation date
     */
    fun findAll(): List<Bill>

    /**
     * Finds a bill by its unique ID.
     *
     * @param billId Unique identifier of the bill
     * @return Bill entity or null if not found
     */
    fun findById(billId: Long): Bill?

    /**
     * Finds a bill by its business bill number.
     *
     * @param billNumber Unique business identifier of the bill
     * @return Bill entity or null if not found
     */
    fun findByBillNumber(billNumber: String): Bill?

    /**
     * Finds all bills for a specific employee.
     *
     * @param employee The employee entity
     * @return List of Bill entities created by the employee
     */
    fun findByEmployee(employee: Employee): List<Bill>

    /**
     * Finds all bills for a specific table session.
     *
     * @param tableSessionId ID of the table session
     * @return List of Bill entities for the table session
     */
    fun findByTableSessionId(tableSessionId: Long): List<Bill>

    /**
     * Finds bills within a specific date range.
     *
     * @param startDate Start date (inclusive)
     * @param endDate End date (inclusive)
     * @return List of Bill entities within the date range
     */
    fun findByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
    ): List<Bill>

    /**
     * Finds bills with specific tip recipient.
     *
     * @param tipRecipient Who receives the tip
     * @return List of Bill entities with the specified tip recipient
     */
    fun findByTipRecipient(tipRecipient: TipRecipient): List<Bill>

    /**
     * Finds bills with total amount within a specific range.
     *
     * @param minAmount Minimum total amount (inclusive)
     * @param maxAmount Maximum total amount (inclusive)
     * @return List of Bill entities within the amount range
     */
    fun findByAmountRange(
        minAmount: BigDecimal,
        maxAmount: BigDecimal,
    ): List<Bill>

    /**
     * Finds bills that have been deleted (with deletion reason).
     *
     * @return List of deleted Bill entities
     */
    fun findDeleted(): List<Bill>

    /**
     * Creates a new bill record.
     *
     * @param employee employee creating the bill
     * @param tableSession Table session for the bill
     * @param billAmount Total bill amount
     * @param tips Tip amount
     * @param billNumber Optional pre-generated bill number (for thread-safe generation)
     * @param billSequence Optional pre-determined bill sequence (for thread-safe generation)
     * @return Created Bill entity
     */
    fun create(
        employee: Employee,
        tableSession: TableSession,
        billAmount: BigDecimal,
        tips: BigDecimal = BigDecimal.ZERO,
        billNumber: String? = null,
        billSequence: Long? = null,
    ): Bill

    /**
     * Counts the total number of bills.
     *
     * @param includeDeleted Whether to include soft-deleted bills
     * @return Total count of bills in the database
     */
    suspend fun count(includeDeleted: Boolean = false): Long

    /**
     * Counts bills for a specific employee.
     *
     * @param employeeId ID of the employee
     * @param includeDeleted Whether to include soft-deleted bills
     * @return Count of bills created by the employee
     */
    suspend fun countByEmployeeId(
        employeeId: String,
        includeDeleted: Boolean = false,
    ): Long

    /**
     * Counts bills within a specific date range.
     *
     * @param startDate Start date (inclusive)
     * @param endDate End date (inclusive)
     * @param includeDeleted Whether to include soft-deleted bills
     * @return Count of bills within the date range
     */
    suspend fun countByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        includeDeleted: Boolean = false,
    ): Long

    /**
     * Calculates total revenue from bills within date range.
     *
     * @param startDate Start date (inclusive)
     * @param endDate End date (inclusive)
     * @param includeDeleted Whether to include soft-deleted bills
     * @return Total revenue amount
     */
    suspend fun getTotalRevenue(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        includeDeleted: Boolean = false,
    ): BigDecimal

    /**
     * Calculates total tips from bills within date range.
     *
     * @param startDate Start date (inclusive)
     * @param endDate End date (inclusive)
     * @param includeDeleted Whether to include soft-deleted bills
     * @return Total tips amount
     */
    suspend fun getTotalTips(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        includeDeleted: Boolean = false,
    ): BigDecimal

    /**
     * Gets bills with pagination support.
     *
     * @param offset Number of records to skip
     * @param limit Maximum number of records to return
     * @param includeDeleted Whether to include soft-deleted bills
     * @return List of Bill entities
     */
    fun findWithPagination(
        offset: Int,
        limit: Int,
        includeDeleted: Boolean = false,
    ): List<Bill>

    /**
     * Searches bills by multiple criteria.
     *
     * @param employeeId Optional employee ID filter
     * @param tableSessionId Optional table session ID filter
     * @param startDate Optional start date filter
     * @param endDate Optional end date filter
     * @param minAmount Optional minimum amount filter
     * @param maxAmount Optional maximum amount filter
     * @param tipRecipient Optional tip recipient filter
     * @param includeDeleted Whether to include soft-deleted bills
     * @param offset Rows to skip, applied in SQL
     * @param limit Maximum rows to return, applied in SQL; null returns every match
     * @param includeTraining Whether to include Schulungsbelege (`is_training`). Off by default — a
     *   revenue-adjacent caller must never see them; a receipt LIST (every signed Beleg of a Z-Bon)
     *   asks for them explicitly.
     * @return List of Bill entities matching criteria, newest close first. Ties on `endTime` are
     *   broken by id (descending), so consecutive pages neither skip nor repeat a bill.
     */
    fun searchBills(
        employeeId: String? = null,
        tableSessionId: Long? = null,
        startDate: LocalDateTime? = null,
        endDate: LocalDateTime? = null,
        minAmount: BigDecimal? = null,
        maxAmount: BigDecimal? = null,
        tipRecipient: TipRecipient? = null,
        includeDeleted: Boolean = false,
        offset: Long = 0,
        limit: Int? = null,
        includeTraining: Boolean = false,
    ): List<Bill>

    /**
     * Finds a bill by its linked TSE transaction number.
     *
     * @param transactionNumber The TSE transaction number from the QR code
     * @return Bill entity or null if not found
     */
    fun findByTseTransactionNumber(transactionNumber: Long): Bill?

    suspend fun generatedBillNumber(): String

    /**
     * Returns true if any non-training bill was closed (endTime) strictly between [start] and [end].
     *
     * Used by monthly-close chain-gap detection to determine whether a coverage gap
     * contains fiscally relevant transactions.
     *
     * @param start Gap start (exclusive lower bound)
     * @param end Gap end (exclusive upper bound)
     * @return true if at least one closed bill exists in the window
     */
    fun existsClosedBillsBetween(
        start: LocalDateTime,
        end: LocalDateTime,
    ): Boolean

    /**
     * Counts non-training bills in [start, end) whose Z-Bon is not yet assigned
     * (zBonId == null) and that have not been deleted (deletingReason == null).
     *
     * Used by the monthly-close checklist to detect bills that were recorded during
     * the period but not yet included in any Z-Bon — i.e., still "open".
     *
     * @param start Window start (inclusive)
     * @param end Window end (exclusive)
     * @return Count of open (unassigned) bills in the window
     */
    fun countOpenInWindow(
        start: LocalDateTime,
        end: LocalDateTime,
    ): Int

    /**
     * Counts non-training bills in [start, end) whose TSE signing status is not SIGNED,
     * CANCELLED, or DISABLED — meaning they still require a TSE signature.
     *
     * Used by the monthly-close checklist to detect bills missing a valid TSE signature,
     * which would be a KassenSichV compliance violation.
     *
     * @param start Window start (inclusive)
     * @param end Window end (exclusive)
     * @return Count of bills with pending/failed/queued TSE signing status
     */
    fun countUnsignedTseInWindow(
        start: LocalDateTime,
        end: LocalDateTime,
    ): Int
}
