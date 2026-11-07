package org.example.project.service

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.bill.RebookFixture
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * A3 Task 3: `bill.terminal` must be persisted on the bill created by `createBill`, AND on the
 * reversal bill produced by `deleteBill` — and on both Belege of a Zahlartwechsel (`rebookBill`), so `TsePendingSweeper` — which reads
 * `bill.terminal?.clientId` to rebuild the signing client for a stranded PENDING bill — is not
 * permanently inert. Before this fix, each call site resolved the terminal only to derive the TSE
 * `clientId` inline and discarded the entity, so `bill.terminal` stayed NULL for every bill/reversal
 * ever created.
 */
@DisplayName("Bill.terminal is persisted on create/delete/rebook (repairs TsePendingSweeper)")
class BillTerminalLinkTest {
    private val deviceId = "btl-device"
    private val terminalClientId = "BTL-CLIENT"

    // BillNumberTracker is a JVM-global singleton; deleteBill reserves a bill number that is only
    // released on failure, so a leaked reservation from one test would collide with the
    // deterministic "RE-yyMMdd-N" number the next test (fresh DB) re-derives.
    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    private fun seedTerminal() =
        transaction {
            val now = currentTimeStamp()
            Terminal.new {
                this.deviceId = this@BillTerminalLinkTest.deviceId
                this.clientId = terminalClientId
                this.registeredAt = now
                this.lastSeenAt = now
            }
        }

    /** Seeds a single 20.00@19% OPEN item on a fresh table session; returns (itemId, sessionId). */
    private fun seedOrderItem(
        admin: Employee,
        tableSuffix: String,
    ): Pair<Long, Long> {
        val vat19 = TestData.createDefaultVatRates().first()
        val tws = TestData.createTableWithSession("BTL_$tableSuffix")
        val category = TestData.createCategory("BTL Cat $tableSuffix")
        val product =
            TestData.createProduct(
                categoryId = category.id.value,
                productName = "BTL Product $tableSuffix",
                productNumber = "BTL_$tableSuffix",
                price = BigDecimal("20.0000"),
            )
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
        return itemId to tws.session.id.value
    }

    @Test
    fun `createBill links the resolved terminal onto the bill`() =
        testApp(
            setupData = {
                val admin = TestData.createAdmin(employeeNumber = "BTL_C", username = "btl_c")
                val cash = TestData.createPaymentType("Cash_C", isCash = true)
                val (itemId, sessionId) = seedOrderItem(admin, "C")
                seedTerminal()

                val billService = GlobalContext.get().get<BillService>()
                val response =
                    runBlocking {
                        billService.createBill(
                            employeeId = admin.id.value.toString(),
                            deviceId = deviceId,
                            request =
                                BillDto.CreateBillRequestDto(
                                    tableSessionId = sessionId,
                                    orderItems = listOf(itemId),
                                    billAmount = "20.00",
                                    tips = "0.00",
                                    payments =
                                        listOf(
                                            PaymentDto.PaymentRequest(amount = "20.00", paymentTypeId = cash.id.value),
                                        ),
                                ),
                        )
                    }

                val persistedClientId =
                    transaction {
                        Bill.findById(response.billId)!!.terminal?.clientId
                    }

                assertEquals(
                    terminalClientId,
                    persistedClientId,
                    "bill.terminal must be set to the terminal resolved via deviceId at creation",
                )
            },
        ) { _ -> }

    @Test
    fun `deleteBill links the resolved terminal onto the reverse bill`() =
        testApp(
            setupData = {
                val admin = TestData.createAdmin(employeeNumber = "BTL_D", username = "btl_d")
                val cash = TestData.createPaymentType("Cash_D", isCash = true)
                val (itemId, sessionId) = seedOrderItem(admin, "D")
                seedTerminal()

                val billService = GlobalContext.get().get<BillService>()
                val billId =
                    runBlocking {
                        billService.createBill(
                            employeeId = admin.id.value.toString(),
                            deviceId = deviceId,
                            request =
                                BillDto.CreateBillRequestDto(
                                    tableSessionId = sessionId,
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

                runBlocking {
                    billService.deleteBill(
                        employeeId = admin.id.value.toString(),
                        deviceId = deviceId,
                        billId = billId.toString(),
                        deletingReason = "terminal-link regression test",
                    )
                }

                val persistedClientId =
                    transaction {
                        Bill
                            .find { BillTable.deletedBillId eq billId }
                            .single()
                            .terminal
                            ?.clientId
                    }

                assertEquals(
                    terminalClientId,
                    persistedClientId,
                    "the reverse bill created by deleteBill must also carry the resolved terminal",
                )
            },
        ) { _ -> }

    @Test
    fun `rebookBill links the resolved terminal onto the Storno and the Neubuchung`() =
        testApp { _ ->
            val fixture = RebookFixture("btl-rebook-device")
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)

            val rebooked =
                fixture.rebook(
                    sale,
                    BillDto.RebookRequestDto(
                        payments = listOf(PaymentDto.PaymentRequest(amount = "23.00", paymentTypeId = sale.cashTypeId)),
                        tips = "0.00",
                    ),
                )

            transaction {
                val storno = Bill.find { BillTable.deletedBillId eq sale.billId }.single()
                assertEquals("TERM-ZWT", storno.terminal?.clientId, "the Storno carries the resolved terminal")
                assertEquals("TERM-ZWT", Bill.findById(rebooked.billId)!!.terminal?.clientId, "the Neubuchung carries it too")
            }
        }
}
