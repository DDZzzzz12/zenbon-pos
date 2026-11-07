package org.example.project.features.tse

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.audit.domain.service.TransactionAuditService
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.repository.TseSigningQueueRepositoryImpl
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.data.service.TsePendingSweeper
import org.example.project.features.tse.data.service.TseSigningQueueServiceImpl
import org.example.project.features.tse.domain.models.TseSigningQueue
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.builder.ProcessDataBuilder
import org.example.project.utils.formatter.currentInstant
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.getTimeZone
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

/**
 * TSER-9 (round-3 audit): bill signing is two transactions — Phase 1 commits the bill with the
 * default `tseSigningStatus = PENDING`, Phase 2 signs on the TSE and enqueues for Nachsignieren.
 * A crash between the two (or an enqueue failure) strands the bill PENDING with NO queue row.
 * The Nachsignieren worker only ever processes queue rows, so nothing would ever sign it — the
 * bill stays unsigned forever while the DSFinV-K export promises a "Nachsignierung ausstehend".
 *
 * These tests pin the sweeper that reconciles PENDING bills against the queue: it enqueues the
 * orphans (rebuilding byte-identical processData via the shared ProcessDataBuilder.buildForBill),
 * never double-enqueues a bill that already has a queue row, and never touches a bill still inside
 * the grace period (which the normal signing path is expected to enqueue itself).
 */
@DisplayName("TsePendingSweeper — enqueues PENDING bills stranded without a queue row (TSER-9)")
@OptIn(ExperimentalTime::class)
class TsePendingSweeperTest {
    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private fun sweeper() =
        TsePendingSweeper(
            TseSigningQueueRepositoryImpl(),
            TseSigningQueueServiceImpl(
                TseSigningQueueRepositoryImpl(),
                TseTransactionRepositoryImpl(),
                HealthyFakeTseSignProvider(),
                NoOpTransactionAuditService(),
            ),
        )

    /**
     * Seeds a forward-sale bill that is PENDING, unsigned, has a terminal (clientId) and a cash
     * payment, aged [ageMinutes] behind now. Returns the bill id and its expected processData.
     */
    private fun seedPendingBill(
        tag: String,
        billNumber: String,
        clientId: String,
        ageMinutes: Long,
    ): Pair<Long, String> {
        var billId = 0L
        var expectedProcessData = ""
        transaction {
            val admin = TestData.createAdmin(employeeNumber = "SWP_$tag", username = "swp_$tag")
            val cash = TestData.createPaymentType("Cash_$tag", isCash = true)
            val tws = TestData.createTableWithSession("T$tag") // table_number column max length 10
            val session = TableSession.findById(tws.session.id)!!
            val ts = currentInstant().minus(ageMinutes.minutes).toLocalDateTime(getTimeZone())

            val terminal =
                Terminal.new {
                    this.deviceId = "dev_$tag"
                    this.clientId = clientId
                    this.registeredAt = currentTimeStamp()
                    this.lastSeenAt = currentTimeStamp()
                    // A3 Task 4: the sweeper now refuses to re-sign under a non-fiscal/unregistered
                    // terminal; this fixture models a properly-provisioned fiscal + TSE-registered one
                    // so these tests keep exercising grace-period/dedup/processData behavior, not the guard.
                    this.tseClientRegistered = true
                }

            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = billNumber
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = ts
                    this.endTime = ts
                    this.tseTransaction = null
                    this.tseSigningStatus = TseSigningStatus.PENDING
                    this.terminal = terminal
                }
            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("10.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = ts
            }
            billId = bill.id.value
            // The processData the live signing path would have produced — the sweeper must match it.
            expectedProcessData = ProcessDataBuilder.buildForBill(bill)
        }
        return billId to expectedProcessData
    }

    @Test
    fun `a PENDING bill older than the grace period with no queue row is enqueued with rebuilt processData`() {
        val (billId, expectedProcessData) =
            seedPendingBill(
                tag = "A",
                billNumber = "SWEEP-A",
                clientId = "POS-SWEEP-1",
                ageMinutes = 30, // well beyond the 10-minute grace period
            )

        val count = runBlocking { sweeper().sweep() }

        assertEquals(1, count, "the single stranded PENDING bill must be enqueued")

        val entry =
            transaction {
                TseSigningQueue.all().toList().firstOrNull {
                    it.referenceId == billId.toString() && it.entityType == TseEntityType.BILL
                }
            }
        assertNotNull(entry, "a queue row (entityType BILL, referenceId = bill id) must now exist")
        assertEquals(TseEntityType.BILL, entry.entityType)
        assertEquals(billId.toString(), entry.referenceId)
        assertEquals("POS-SWEEP-1", entry.clientId, "the clientId must come from the bill's terminal")
        assertEquals(
            expectedProcessData,
            entry.processData,
            "the sweeper must rebuild BYTE-IDENTICAL processData via the shared buildForBill helper",
        )
    }

    @Test
    fun `a PENDING bill that already has a queue row is not enqueued twice`() {
        val (billId, _) =
            seedPendingBill(
                tag = "B",
                billNumber = "SWEEP-B",
                clientId = "POS-SWEEP-2",
                ageMinutes = 30,
            )

        // Pre-existing queue row: the worker path already owns this Vorgang.
        transaction {
            TseSigningQueue.new {
                this.referenceId = billId.toString()
                this.entityType = TseEntityType.BILL
                this.processType = "Kassenbeleg-V1"
                this.processData = "PRE-EXISTING"
                this.clientId = "POS-SWEEP-2"
                this.signingStatus = TseSigningStatus.QUEUED
                this.retryCount = 0
                this.maxRetries = 10
                this.createdAt = currentTimeStamp()
                this.priority = 1
            }
        }

        val count = runBlocking { sweeper().sweep() }

        assertEquals(0, count, "an already-queued bill must not be swept")
        val rows =
            transaction {
                TseSigningQueue.all().toList().filter { it.referenceId == billId.toString() }
            }
        assertEquals(1, rows.size, "no duplicate queue row may be created")
        assertEquals("PRE-EXISTING", rows.single().processData, "the existing row must be untouched")
    }

    @Test
    fun `a PENDING bill newer than the grace period is skipped`() {
        val (billId, _) =
            seedPendingBill(
                tag = "C",
                billNumber = "SWEEP-C",
                clientId = "POS-SWEEP-3",
                ageMinutes = 1, // inside the 10-minute grace period
            )

        val count = runBlocking { sweeper().sweep() }

        assertEquals(0, count, "a bill inside the grace period must not be swept (the signing path owns it)")
        val entry =
            transaction {
                TseSigningQueue.all().toList().firstOrNull { it.referenceId == billId.toString() }
            }
        assertNull(entry, "no queue row may be created for a bill still inside the grace period")
    }

    private open class HealthyFakeTseSignProvider : TseSignProvider {
        override suspend fun quickSign(
            processType: String,
            processData: String,
            clientId: String,
        ): TseDto.SignResultDto = throw NotImplementedError()

        override suspend fun startTransaction(
            processType: String,
            clientId: String,
        ): TseDto.TransactionStartResultDto = throw NotImplementedError()

        override suspend fun updateTransaction(
            transactionNumber: Long,
            processData: String,
            clientId: String,
        ): TseDto.SignResultDto = throw NotImplementedError()

        override suspend fun finishTransaction(
            transactionNumber: Long,
            processData: String,
            clientId: String,
            processType: String,
        ): TseDto.SignResultDto = throw NotImplementedError()

        override suspend fun checkHealth(): TseDto.HealthCheckResultDto =
            TseDto.HealthCheckResultDto(
                healthy = true,
                initialized = true,
                validTime = true,
                passedSelfTest = true,
                remainingSignatures = 1_000L,
                availableCapacity = 1_000L,
            )

        override fun isConnected(): Boolean = true

        override fun getCachedMode(): String = "SIMULATION"

        override fun registerConnectionListener() = Unit

        override suspend fun restartConnection(): TseDto.HealthCheckResultDto? = null

        override fun close() = Unit
    }

    private class NoOpTransactionAuditService : TransactionAuditService {
        override suspend fun logTransaction(
            employeeId: String,
            employeeName: String?,
            employeeNumber: String,
            authorizationType: String,
            authorizingManagerId: String,
            operation: String,
            entityType: String,
            entityId: String,
            entityName: String?,
            beforeState: Any?,
            afterState: Any?,
            deviceId: String?,
            ipAddress: String?,
            reason: String?,
            amountImpact: Double?,
            fiscalRecordId: String?,
            metadata: Map<String, Any>?,
            traceId: String?,
        ): String = "AUDIT-NOOP"

        override suspend fun getTransactionLogs(query: TransactionAuditDto.TransactionAuditQueryDto): List<TransactionAuditDto.TransactionAuditLogDto> =
            emptyList()

        override suspend fun getTransactionById(transactionId: String): TransactionAuditDto.TransactionAuditLogDto = throw NotImplementedError()

        override suspend fun getEmployeeTransactions(
            employeeId: String,
            limit: Int,
        ): List<TransactionAuditDto.TransactionAuditLogDto> = emptyList()

        override suspend fun getEntityTransactions(
            entityType: String,
            entityId: String,
        ): List<TransactionAuditDto.TransactionAuditLogDto> = emptyList()

        override suspend fun getPaginatedTransactionLogs(
            query: TransactionAuditDto.PaginatedTransactionAuditQueryDto,
        ): TransactionAuditDto.PaginatedTransactionAuditResponseDto = throw NotImplementedError()
    }
}
