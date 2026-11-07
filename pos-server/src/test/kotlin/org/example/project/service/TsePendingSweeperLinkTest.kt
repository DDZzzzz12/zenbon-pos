package org.example.project.service

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.audit.domain.service.TransactionAuditService
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.repository.TseSigningQueueRepositoryImpl
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.data.service.TsePendingSweeper
import org.example.project.features.tse.data.service.TseSigningQueueServiceImpl
import org.example.project.features.tse.domain.models.TseSigningQueue
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentInstant
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.getTimeZone
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

/**
 * A3 Task 3 regression: [TsePendingSweeper] reads `bill.terminal?.clientId` to rebuild the signing
 * client for a bill stranded PENDING with no queue row (TSER-9). Before this fix,
 * `BillServiceImpl.createBill` never wrote `bill.terminal`, so that null-check ALWAYS fired and the
 * sweeper silently skipped every real bill it exists to rescue.
 *
 * Unlike [org.example.project.features.tse.TsePendingSweeperTest]'s fixture (which sets
 * `this.terminal = terminal` by hand on a manually-built `Bill.new { ... }`), this test creates the
 * bill via the REAL production `BillServiceImpl.createBill` path and then only simulates the
 * crash-between-phases staleness (PENDING + aged past the grace period) — proving the production
 * code, not the test fixture, is what populates `bill.terminal`.
 */
@DisplayName("TsePendingSweeper rebuilds a production-created bill's signing client (A3 Task 3)")
@OptIn(ExperimentalTime::class)
class TsePendingSweeperLinkTest {
    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

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

    @Test
    fun `a bill created via the real createBill path is enqueued once stranded PENDING`() =
        testApp(
            setupData = {
                val admin = TestData.createAdmin(employeeNumber = "SWPL_A", username = "swpl_a")
                val cash = TestData.createPaymentType("Cash_SWPL", isCash = true)
                val vat19 = TestData.createDefaultVatRates().first()
                val tws = TestData.createTableWithSession("SWPL")
                val category = TestData.createCategory("SWPL Cat")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "SWPL Product",
                        productNumber = "SWPL_P",
                        price = BigDecimal("20.0000"),
                    )
                val deviceId = "swpl-device"
                val terminalClientId = "SWPL-CLIENT"
                transaction {
                    val now = currentTimeStamp()
                    Terminal.new {
                        this.deviceId = deviceId
                        this.clientId = terminalClientId
                        this.registeredAt = now
                        this.lastSeenAt = now
                        // A3 Task 4: the sweeper now refuses to re-sign under a non-fiscal/unregistered
                        // terminal; this test is about the entity-link plumbing, not that guard, so model
                        // a properly-provisioned fiscal + TSE-registered terminal.
                        this.tseClientRegistered = true
                    }
                }

                val itemId =
                    transaction {
                        val session = TableSession.findById(tws.session.id)!!
                        session.employeeId = admin
                        OrderItem
                            .new {
                                this.session = session
                                this.product = product
                                this.unitPrice = BigDecimal("20.00")
                                this.finalPrice = BigDecimal("20.00")
                                this.itemStatus = ItemStatus.OPEN
                                this.vat = vat19
                                this.discount = BigDecimal.ONE
                                this.customPrice = false
                                this.takeAway = false
                                this.transferred = false
                                this.sideNotes = null
                            }.id.value
                    }

                val billService = GlobalContext.get().get<BillService>()
                val billId =
                    runBlocking {
                        billService.createBill(
                            employeeId = admin.id.value.toString(),
                            deviceId = deviceId,
                            request =
                                BillDto.CreateBillRequestDto(
                                    tableSessionId = tws.session.id.value,
                                    orderItems = listOf(itemId),
                                    billAmount = "20.00",
                                    tips = "0.00",
                                    payments =
                                        listOf(
                                            PaymentDto.PaymentRequest(amount = "20.00", paymentTypeId = cash.id.value),
                                        ),
                                ),
                        )
                    }.billId

                // Simulate the TSER-9 crash-between-phases scenario on a REAL production-created bill:
                // force it PENDING with no queue row (createBill's own Phase 2 already ran to
                // completion under the test config's DISABLED TSE mode), and backdate past the
                // sweeper's grace period.
                transaction {
                    val bill = Bill.findById(billId)!!
                    bill.tseSigningStatus = TseSigningStatus.PENDING
                    bill.endTime = currentInstant().minus(30.minutes).toLocalDateTime(getTimeZone())
                }

                val count = runBlocking { sweeper().sweep() }

                assertEquals(
                    1,
                    count,
                    "the sweeper must enqueue the stranded bill now that createBill persists bill.terminal",
                )

                val entry =
                    transaction {
                        TseSigningQueue
                            .all()
                            .toList()
                            .firstOrNull { it.referenceId == billId.toString() && it.entityType == TseEntityType.BILL }
                    }
                assertNotNull(entry, "a queue row must now exist for the production-created bill")
                assertEquals(
                    terminalClientId,
                    entry.clientId,
                    "the rebuilt queue row's clientId must come from the bill's persisted terminal",
                )
            },
        ) { _ -> }

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
