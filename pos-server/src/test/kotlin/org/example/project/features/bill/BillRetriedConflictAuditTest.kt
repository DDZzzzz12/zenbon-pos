package org.example.project.features.bill

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.domain.models.TransactionAuditLog
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.data.service.BillServiceImpl
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.repository.BillRepository
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.service.TseSaleSigningGateway
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Real-TSE QA rt14 (b): two of five parallel bills lost the `bill_number_sequence` row lock, were
 * retried and saved — yet each attempt left an ERROR stack trace and a FAILED `CREATE_BILL` audit
 * row (entity "FAILED") for a bill that exists. The audit sat INSIDE the retry loop. Only the final
 * outcome may be audited: one SUCCESS row when a retry resolves the conflict, one FAILED row when
 * it does not.
 *
 * The conflict is injected where it happened — drawing the bill number — as H2's "concurrent
 * update" (the engine's serialization failure). It fails the first [CONFLICTS] draws: that outlasts
 * Exposed's own in-transaction retries, so at least one conflict reaches the service's retry loop
 * whatever Exposed is configured to do (memory: a one-shot SQLException double proves nothing).
 */
@DisplayName("createBill — a retried bill-number conflict is not audited or logged as a failure")
class BillRetriedConflictAuditTest {
    private val deviceId = "retry-audit-device"

    @AfterEach
    fun reset() {
        BillNumberTracker.clearAll()
    }

    /** Throws a serialization conflict on the first [failures] number draws, then delegates. */
    private class ConflictingNumbers(
        private val delegate: BillRepository,
        private val failures: Int,
    ) : BillRepository by delegate {
        var draws = 0

        override suspend fun generatedBillNumber(): String {
            draws++
            if (draws <= failures) throw SQLException("Concurrent update in table \"BILL_NUMBER_SEQUENCE\"", "90131")
            return delegate.generatedBillNumber()
        }
    }

    private fun billService(repository: BillRepository): BillServiceImpl {
        val koin = GlobalContext.get()
        return BillServiceImpl(
            repository = repository,
            tableSessionRepository = koin.get(),
            printerService = koin.get(),
            saleSigning = TseSaleSigningGateway(koin.get(), koin.get(), ShopConfigurationManager, koin.get()),
            terminalRepository = koin.get(),
            configManager = ShopConfigurationManager,
            tableRepository = koin.get(),
            invoices = SalesInvoiceBillInvoiceLink(),
        )
    }

    private class Sale(
        val employeeId: String,
        val request: BillDto.CreateBillRequestDto,
    )

    private fun seedSale(): Sale {
        val admin = TestData.createAdmin(employeeNumber = "RTRY_A", username = "rtry_a")
        val cash = TestData.createPaymentType("Bar_RTRY", isCash = true)
        val vat19 = TestData.createDefaultVatRates().first()
        val tws = TestData.createTableWithSession("RTRY_1")
        val category = TestData.createCategory("RTRY Cat")
        val product =
            TestData.createProduct(
                categoryId = category.id.value,
                productName = "RTRY Product",
                productNumber = "RTRY_1",
                price = BigDecimal("4.5000"),
            )
        val itemId =
            transaction {
                val now = currentTimeStamp()
                Terminal.new {
                    this.deviceId = this@BillRetriedConflictAuditTest.deviceId
                    this.clientId = "RTRY-CLIENT"
                    this.registeredAt = now
                    this.lastSeenAt = now
                }
                val session = TableSession.findById(tws.session.id)!!
                session.employeeId = admin
                OrderItem
                    .new {
                        this.session = session
                        this.product = product
                        this.unitPrice = BigDecimal("4.50")
                        this.finalPrice = BigDecimal("4.50")
                        this.itemStatus = ItemStatus.OPEN
                        this.vat = vat19
                        this.discount = BigDecimal.ONE
                        this.customPrice = false
                        this.takeAway = false
                        this.transferred = false
                        this.sideNotes = null
                    }.id.value
            }
        return Sale(
            employeeId = admin.id.value.toString(),
            request =
                BillDto.CreateBillRequestDto(
                    tableSessionId = tws.session.id.value,
                    orderItems = listOf(itemId),
                    billAmount = "4.50",
                    tips = "0.00",
                    payments = listOf(PaymentDto.PaymentRequest("4.50", cash.id.value)),
                ),
        )
    }

    private fun createBillRows() = transaction { TransactionAuditLog.all().filter { it.operation == "CREATE_BILL" }.toList() }

    @Test
    fun `a conflict a retry resolves writes one SUCCESS row and no FAILED row`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val sale = seedSale()
            val repository = ConflictingNumbers(GlobalContext.get().get(), failures = CONFLICTS)

            val reply = runBlocking { billService(repository).createBill(sale.employeeId, deviceId, sale.request) }

            assertTrue(repository.draws > CONFLICTS, "the harness must have conflicted and then drawn a number")
            assertTrue(transaction { Bill.findById(reply.billId) != null }, "the bill exists")
            val rows = createBillRows()
            assertEquals(
                listOf(reply.billId.toString()),
                rows.map { it.entityId },
                "one row, for the bill that was created — no FAILED row for a retried attempt: " +
                    rows.map { "${it.entityId}/${it.changesSummary}" },
            )
            assertTrue(
                rows
                    .single()
                    .changesSummary
                    .orEmpty()
                    .startsWith("SUCCESS"),
                rows.single().changesSummary,
            )
        }

    @Test
    fun `a conflict that never resolves writes exactly one FAILED row and answers 409`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val sale = seedSale()
            val repository = ConflictingNumbers(GlobalContext.get().get(), failures = Int.MAX_VALUE)

            val e =
                assertFailsWith<PosException> {
                    runBlocking { billService(repository).createBill(sale.employeeId, deviceId, sale.request) }
                }

            assertEquals(PosErrorCode.BILL_NUMBER_COLLISION, e.code)
            val rows = createBillRows()
            assertEquals(1, rows.size, "the final outcome is audited once, not once per attempt: ${rows.map { it.changesSummary }}")
            assertTrue(
                rows
                    .single()
                    .changesSummary
                    .orEmpty()
                    .startsWith("FAILED"),
                rows.single().changesSummary,
            )
        }

    private companion object {
        const val CONFLICTS = 3
    }
}
