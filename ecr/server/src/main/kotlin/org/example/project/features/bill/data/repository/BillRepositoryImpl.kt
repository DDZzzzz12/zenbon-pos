package org.example.project.features.bill.data.repository

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDate
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.features.bill.BILL_NUMBER_PREFIX
import org.example.project.features.bill.database.tables.BillNumberSequenceTable
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.repository.BillRepository
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.tse.database.tables.TseTransactionTable
import org.example.project.utils.TrainingFilter
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Implementation of BillRepository using Exposed ORM.
 *
 * Returns plain entities; exceptions propagate naturally to the service layer.
 */
class BillRepositoryImpl : BillRepository {
    private val logger = LoggerFactory.getLogger(BillRepositoryImpl::class.java)

    override fun findAll(): List<Bill> {
        val bills =
            Bill
                .find { TrainingFilter.billNotTraining() }
                .orderBy(BillTable.endTime to SortOrder.DESC)
                .toList()
        logger.debug("Retrieved ${bills.size} bills")
        return bills
    }

    override fun findById(billId: Long): Bill? {
        val bill = Bill.findById(billId)
        if (bill != null) {
            logger.debug("Found bill: $billId")
        } else {
            logger.debug("Bill not found: $billId")
        }
        return bill
    }

    override fun findByBillNumber(billNumber: String): Bill? {
        val bill = Bill.find { BillTable.billNumber eq billNumber }.singleOrNull()
        if (bill != null) {
            logger.debug("Found bill by number: $billNumber")
        } else {
            logger.debug("Bill not found by number: $billNumber")
        }
        return bill
    }

    override fun findByEmployee(employee: Employee): List<Bill> {
        val bills =
            Bill
                .find { (BillTable.employeeId eq employee.id.value) and TrainingFilter.billNotTraining() }
                .orderBy(BillTable.endTime to SortOrder.DESC)
                .toList()
        logger.debug("Found {} bills for employee: {}", bills.size, employee)
        return bills
    }

    override fun findByTableSessionId(tableSessionId: Long): List<Bill> {
        val bills =
            Bill
                .find { BillTable.tableSession eq tableSessionId }
                .orderBy(BillTable.endTime to SortOrder.DESC)
                .toList()
        logger.debug("Found ${bills.size} bills for table session: $tableSessionId")
        return bills
    }

    override fun findByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
    ): List<Bill> {
        val bills =
            Bill
                .find {
                    (BillTable.endTime greaterEq startDate) and (BillTable.endTime lessEq endDate) and
                        TrainingFilter.billNotTraining()
                }.orderBy(BillTable.endTime to SortOrder.DESC)
                .toList()
        logger.debug("Found ${bills.size} bills in date range: $startDate to $endDate")
        return bills
    }

    override fun findByTipRecipient(tipRecipient: TipRecipient): List<Bill> {
        val bills =
            Bill
                .find { (BillTable.tipRecipient eq tipRecipient) and TrainingFilter.billNotTraining() }
                .orderBy(BillTable.endTime to SortOrder.DESC)
                .toList()
        logger.debug("Found ${bills.size} bills for tip recipient: $tipRecipient")
        return bills
    }

    override fun findByAmountRange(
        minAmount: BigDecimal,
        maxAmount: BigDecimal,
    ): List<Bill> {
        val bills =
            Bill
                .find {
                    (BillTable.paidAmount greaterEq minAmount) and (BillTable.paidAmount lessEq maxAmount) and
                        TrainingFilter.billNotTraining()
                }.orderBy(BillTable.paidAmount to SortOrder.DESC)
                .toList()
        logger.debug("Found ${bills.size} bills in amount range: $minAmount to $maxAmount")
        return bills
    }

    override fun findDeleted(): List<Bill> {
        val bills =
            Bill
                .find { BillTable.deletedBillId.isNotNull() and TrainingFilter.billNotTraining() }
                .orderBy(BillTable.endTime to SortOrder.DESC)
                .toList()
        logger.debug("Found ${bills.size} deleted bills")
        return bills
    }

    override fun create(
        employee: Employee,
        tableSession: TableSession,
        billAmount: BigDecimal,
        tips: BigDecimal,
        billNumber: String?,
        billSequence: Long?,
    ): Bill {
        val timestamp = currentTimeStamp()
        // Create bill entity
        val bill =
            Bill.new {
                this.employeeId = employee
                this.tipRecipient = if (employee.role != EmployeeRole.ADMIN) TipRecipient.EMPLOYEE else TipRecipient.OWNER
                this.tableSession = tableSession
                this.billAmount = billAmount
                this.paidAmount = BigDecimal.ZERO // Initialize to zero, will be updated after payments
                this.tips = tips
                this.startTime = tableSession.startTime
                this.endTime = timestamp // Initialize with current time, will be updated if needed

                // Use pre-generated bill number if provided, otherwise use temporary timestamp
                this.billNumber = billNumber ?: timestamp.toString()

                // Note: billSequence is auto-increment and cannot be manually set
                // It will be automatically assigned by the database
            }

        logger.info("Bill entity created: ID=${bill.id.value}, number=${bill.billNumber}")
        return bill
    }

    override suspend fun count(includeDeleted: Boolean): Long =
        try {
            val baseFilter = TrainingFilter.billNotTraining()
            val query =
                if (includeDeleted) {
                    BillTable.selectAll().where { baseFilter }
                } else {
                    BillTable.selectAll().where { BillTable.deletedBillId.isNull() and baseFilter }
                }
            query.count()
        } catch (e: Exception) {
            logger.error("Failed to count bills", e)
            0L
        }

    override suspend fun countByEmployeeId(
        employeeId: String,
        includeDeleted: Boolean,
    ): Long =
        try {
            val baseCondition = (BillTable.employeeId eq UUID.fromString(employeeId)) and TrainingFilter.billNotTraining()
            val condition =
                if (includeDeleted) {
                    baseCondition
                } else {
                    baseCondition and BillTable.deletingReason.isNull()
                }
            BillTable.selectAll().where { condition }.count()
        } catch (e: Exception) {
            logger.error("Failed to count bills by employee ID: $employeeId", e)
            0L
        }

    override suspend fun countByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        includeDeleted: Boolean,
    ): Long =
        try {
            val dateCondition =
                (BillTable.endTime greaterEq startDate) and (BillTable.endTime lessEq endDate) and
                    TrainingFilter.billNotTraining()
            val condition =
                if (includeDeleted) {
                    dateCondition
                } else {
                    dateCondition and BillTable.deletingReason.isNull()
                }
            BillTable.selectAll().where { condition }.count()
        } catch (e: Exception) {
            logger.error("Failed to count bills by date range", e)
            0L
        }

    override suspend fun getTotalRevenue(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        includeDeleted: Boolean,
    ): BigDecimal =
        try {
            val dateCondition =
                (BillTable.endTime greaterEq startDate) and (BillTable.endTime lessEq endDate) and
                    TrainingFilter.billNotTraining()
            val condition =
                if (includeDeleted) {
                    dateCondition
                } else {
                    dateCondition and BillTable.deletingReason.isNull()
                }

            BillTable
                .selectAll()
                .where { condition }
                .sumOf { it[BillTable.billAmount] }
        } catch (e: Exception) {
            logger.error("Failed to calculate total revenue", e)
            BigDecimal.ZERO
        }

    override suspend fun getTotalTips(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        includeDeleted: Boolean,
    ): BigDecimal =
        try {
            val dateCondition =
                (BillTable.endTime greaterEq startDate) and (BillTable.endTime lessEq endDate) and
                    TrainingFilter.billNotTraining()
            val condition =
                if (includeDeleted) {
                    dateCondition
                } else {
                    dateCondition and BillTable.deletingReason.isNull()
                }

            BillTable
                .selectAll()
                .where { condition }
                .sumOf { it[BillTable.tips] }
        } catch (e: Exception) {
            logger.error("Failed to calculate total tips", e)
            BigDecimal.ZERO
        }

    override fun findWithPagination(
        offset: Int,
        limit: Int,
        includeDeleted: Boolean,
    ): List<Bill> {
        val condition =
            if (includeDeleted) {
                TrainingFilter.billNotTraining()
            } else {
                BillTable.deletingReason.isNull() and TrainingFilter.billNotTraining()
            }

        val bills =
            Bill
                .find { condition }
                .orderBy(BillTable.endTime to SortOrder.DESC, BillTable.id to SortOrder.DESC)
                .limit(limit)
                .offset(offset.toLong())
                .toList()

        logger.debug("Retrieved ${bills.size} bills with pagination (offset: $offset, limit: $limit)")
        return bills
    }

    override fun searchBills(
        employeeId: String?,
        tableSessionId: Long?,
        startDate: LocalDateTime?,
        endDate: LocalDateTime?,
        minAmount: BigDecimal?,
        maxAmount: BigDecimal?,
        tipRecipient: TipRecipient?,
        includeDeleted: Boolean,
        offset: Long,
        limit: Int?,
        includeTraining: Boolean,
    ): List<Bill> {
        var condition: Op<Boolean> = if (includeTraining) Op.TRUE else TrainingFilter.billNotTraining()
        if (!includeDeleted) condition = condition and BillTable.deletingReason.isNull()

        employeeId?.let { condition = condition and (BillTable.employeeId eq UUID.fromString(it)) }
        tableSessionId?.let { condition = condition and (BillTable.tableSession eq it) }
        startDate?.let { condition = condition and (BillTable.endTime greaterEq it) }
        endDate?.let { condition = condition and (BillTable.endTime lessEq it) }
        minAmount?.let { condition = condition and (BillTable.paidAmount greaterEq it) }
        maxAmount?.let { condition = condition and (BillTable.paidAmount lessEq it) }
        tipRecipient?.let { condition = condition and (BillTable.tipRecipient eq it) }

        // endTime alone is not a total order: bills closed in the same second would come back in
        // an arbitrary order per query, and an offset-paged reader (searchAllReceipts) could skip
        // one and repeat another across pages. The id breaks the tie deterministically.
        val ordered =
            Bill
                .find { condition }
                .orderBy(BillTable.endTime to SortOrder.DESC, BillTable.id to SortOrder.DESC)
        val bills =
            if (limit == null && offset == 0L) {
                ordered.toList()
            } else {
                ordered.limit(limit ?: Int.MAX_VALUE).offset(offset).toList()
            }

        logger.debug("Found ${bills.size} bills matching search criteria")
        return bills
    }

    override fun findByTseTransactionNumber(transactionNumber: Long): Bill? {
        val bill =
            Bill
                .find {
                    BillTable.tseTransactionId inSubQuery
                        TseTransactionTable
                            .select(TseTransactionTable.id)
                            .where { TseTransactionTable.transactionNumber eq transactionNumber }
                }.singleOrNull()

        if (bill != null) {
            logger.debug("Found bill by TSE transaction number: $transactionNumber")
        } else {
            logger.debug("Bill not found by TSE transaction number: $transactionNumber")
        }
        return bill
    }

    override fun existsClosedBillsBetween(
        start: LocalDateTime,
        end: LocalDateTime,
    ): Boolean =
        Bill
            .find {
                (BillTable.endTime greater start) and (BillTable.endTime less end) and
                    TrainingFilter.billNotTraining()
            }.limit(1)
            .count() > 0

    override fun countOpenInWindow(
        start: LocalDateTime,
        end: LocalDateTime,
    ): Int =
        BillTable
            .selectAll()
            .where {
                (BillTable.endTime greaterEq start) and
                    (BillTable.endTime less end) and
                    BillTable.zBonId.isNull() and
                    BillTable.deletingReason.isNull() and
                    TrainingFilter.billNotTraining()
            }.count()
            .toInt()

    override fun countUnsignedTseInWindow(
        start: LocalDateTime,
        end: LocalDateTime,
    ): Int {
        // M8: CANCELLED was listed here, so a Beleg whose Nachsignierung an operator deliberately
        // abandoned counted as "signed" and the monthly close reported PASSED over a Beleg carrying
        // no TSE signature at all. An abandoned signature is still an unsigned Beleg (§146a AO) and
        // must be visible while the period is being closed.
        //
        // DISABLED and SIMULATION stay excluded: those are configuration states covering the whole
        // installation, not per-Vorgang operator decisions, and surfacing them here would mark every
        // Beleg of a TSE-less setup rather than pointing at anything actionable.
        val signedStatuses =
            listOf(
                org.example.project.core.domain.enums.tse.TseSigningStatus.SIGNED,
                org.example.project.core.domain.enums.tse.TseSigningStatus.DISABLED,
                org.example.project.core.domain.enums.tse.TseSigningStatus.SIMULATION,
            )
        return BillTable
            .selectAll()
            .where {
                (BillTable.endTime greaterEq start) and
                    (BillTable.endTime less end) and
                    (BillTable.tseSigningStatus notInList signedStatuses) and
                    TrainingFilter.billNotTraining()
            }.count()
            .toInt()
    }

    /**
     * Issues the next Kassenbeleg number, `RE-yyMMdd-N`, with N counted per month.
     *
     * N comes from the month's row in [BillNumberSequenceTable], locked `FOR UPDATE` and incremented
     * inside the caller's transaction: two concurrent issuances serialise on that row, and a bill
     * that rolls back returns its number, so the month's sequence stays unique and gapless. This
     * replaced a `count(*)` of the month's bills, which two parallel transactions read alike (real-TSE
     * QA rt12: duplicate `RE-260506-39`, one bill died with a 500).
     *
     * The month's row is created on its first issuance, seeded past every number already issued in
     * that month ([firstValueFor]), so an install upgraded mid-month continues its sequence instead
     * of restarting it. Concurrent creators meet in `INSERT … ON CONFLICT DO NOTHING`; under
     * REPEATABLE_READ the loser gets a serialization failure and the caller retries.
     *
     * The date and the month both come from [currentTimeStamp] (the shop's time zone), the clock
     * that also stamps `bill.end_time`. Requires an ambient transaction.
     */
    override suspend fun generatedBillNumber(): String {
        val today = currentTimeStamp().date
        val dateString = today.toJavaLocalDate().format(DateTimeFormatter.ofPattern("yyMMdd"))
        val period = dateString.substring(0, 4)

        val issued =
            lockedNextValue(period) ?: run {
                BillNumberSequenceTable.insertIgnore {
                    it[BillNumberSequenceTable.period] = period
                    it[nextValue] = firstValueFor(period)
                }
                checkNotNull(lockedNextValue(period)) { "bill_number_sequence row for $period vanished after insert" }
            }
        BillNumberSequenceTable.update({ BillNumberSequenceTable.period eq period }) {
            it[nextValue] = issued + 1
        }

        // Format: RE-YYMMDD-SEQUENCE — see BILL_NUMBER_PREFIX for why the prefix is a constant.
        return "$BILL_NUMBER_PREFIX-$dateString-$issued"
    }

    /** The month's [BillNumberSequenceTable.nextValue], row-locked until the transaction ends; null before its first bill. */
    private fun lockedNextValue(period: String): Long? =
        BillNumberSequenceTable
            .selectAll()
            .where { BillNumberSequenceTable.period eq period }
            .forUpdate()
            .singleOrNull()
            ?.get(BillNumberSequenceTable.nextValue)

    /**
     * Where a month's counter starts: one past the highest N already issued in that month, or 0 for
     * a month without bills — the value the old `count(*)` gave a month's first bill, so the format
     * of a fresh month is unchanged.
     */
    private fun firstValueFor(period: String): Long =
        BillTable
            .select(BillTable.billNumber)
            .where { BillTable.billNumber like "$BILL_NUMBER_PREFIX-$period%" }
            .mapNotNull { it[BillTable.billNumber].substringAfterLast('-').toLongOrNull() }
            .maxOrNull()
            ?.plus(1) ?: 0L
}
