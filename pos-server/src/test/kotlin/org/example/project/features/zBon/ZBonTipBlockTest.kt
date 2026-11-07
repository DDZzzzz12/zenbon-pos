package org.example.project.features.zBon

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.utils.zBonTipSections
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.product.domain.models.Product
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * QA emu14 NOTE-5: the Z-Bon's tip block listed every employee's tip, the owner's included, under a
 * total that left the owner out — M001 4 + A001 4 + K001 2 listed, 6.00 totalled.
 *
 * One day, two bills closed through the real [BillService]: a MANAGER's with a 3.00 employee tip
 * (TrinkgeldAN, not revenue) and an ADMIN's with a 2.00 owner tip (TrinkgeldAG, taxed turnover).
 * The response must mark the owner's summary, total only the employee tip, report the owner tip as
 * `adminTip`, and leave every fiscal figure exactly where it was.
 */
@DisplayName("Z-Bon — the tip block separates employee tips from the owner tip (QA emu14 NOTE-5)")
class ZBonTipBlockTest {
    private val deviceId = "zbon-tip-block-device"

    /** What every bill of the day is sold from. */
    private data class Day(
        val product: Product,
        val vat: Vat,
        val cashPaymentTypeId: Long,
    )

    private var billIds: List<Long> = emptyList()

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    @Test
    fun `employee tips are listed and totalled, the owner tip stands apart, revenue is unchanged`() =
        testApp(setupData = { setupMixedTipDay() }) { client ->
            transaction {
                val recipients = billIds.map { Bill.findById(it)!!.tipRecipient }
                assertEquals(listOf(TipRecipient.EMPLOYEE, TipRecipient.OWNER), recipients, "fixture sanity")
            }

            val dto = createClosing(client)
            val summaries = assertNotNull(dto.employeeSummaries)
            val manager = summaries.single { it.employeeNumber == "ZTB_MGR" }
            val admin = summaries.single { it.employeeNumber == "ZTB_ADM" }

            assertEquals(false, manager.ownerTip, "a MANAGER's tip is an employee tip")
            assertEquals(0, BigDecimal("3.00").compareTo(BigDecimal(manager.amount)))
            // The owner tip lives in adminTip alone; the admin's summary carries no tip.
            assertEquals(false, admin.ownerTip)
            assertEquals(0, BigDecimal.ZERO.compareTo(BigDecimal(admin.amount)), "no owner tip on a summary")

            assertEquals("3.00", dto.totalEmployeeTips, "only the employee tip is totalled")
            assertEquals("2.00", dto.adminTip, "the owner tip is reported as revenue")
            // 20.00 + 20.00 turnover + the 2.00 owner tip; the 3.00 employee tip is not revenue.
            assertEquals("42.00", dto.totalRevenue)
            val vatGross = assertNotNull(dto.vatGroups).sumOf { BigDecimal(it.grossAmount) }
            assertEquals(0, BigDecimal("42.00").compareTo(vatGross), "the VAT groups agree with the revenue")
            assertEquals("45.00", dto.closingBalance, "every cent tendered is in the drawer")

            val tips = zBonTipSections(dto)
            assertEquals(listOf("ZTB_MGR"), tips.employeeTips.map { it.employeeNumber }, "the owner is not listed")
            assertEquals(
                0,
                tips.employeeTips.sumOf { BigDecimal(it.amount) }.compareTo(BigDecimal(assertNotNull(tips.employeeTipTotal))),
                "the total is the sum of the listed lines",
            )
            assertEquals("2.00", tips.ownerTip)
        }

    private suspend fun createClosing(client: io.ktor.client.HttpClient): ZBonDto.ZBonResponseDto {
        val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
        val response = client.post("/api/z-bon") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Created, response.status, "body: ${response.bodyAsText()}")
        return testJson.decodeFromString<ZBonDto.ZBonResponseDto>(response.bodyAsText())
    }

    private fun setupMixedTipDay() {
        TestData.createShopInfo()
        val admin = TestData.createAdmin(employeeNumber = "ZTB_ADM", username = "admin")
        val manager = TestData.createManager(employeeNumber = "ZTB_MGR", username = "ztb_mgr")
        val cash = TestData.createPaymentType("Cash_ZTB", isCash = true)
        val vat19 = TestData.createDefaultVatRates().first()
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TERM-ZTB")
        transaction {
            // H2 stand-in for the Postgres advisory lock used to serialize Z-Bon creation.
            exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")
        }
        val category = TestData.createCategory("ZTB Cat")
        val product = TestData.createProduct(category.id.value, "ZTB Product", "ZTB_P", BigDecimal("20.0000"))
        val day = Day(product, vat19, cash.id.value)
        billIds = listOf(closeTippedBill(manager, "ZTB1", day, tip = "3.00"), closeTippedBill(admin, "ZTB2", day, tip = "2.00"))
    }

    /** One 20.00 item closed by [closer] with [tip], all tendered in cash; the table is then freed. */
    private fun closeTippedBill(
        closer: Employee,
        tableName: String,
        day: Day,
        tip: String,
    ): Long {
        val tws = TestData.createTableWithSession(tableName)
        val itemId =
            transaction {
                val session = TableSession.findById(tws.session.id)!!
                session.employeeId = closer
                OrderItem
                    .new {
                        this.session = session
                        this.product = day.product
                        this.unitPrice = BigDecimal("20.00")
                        this.finalPrice = BigDecimal("20.00")
                        this.itemStatus = ItemStatus.OPEN
                        this.vat = day.vat
                        this.discount = BigDecimal.ONE
                        this.customPrice = false
                        this.takeAway = false
                        this.transferred = false
                        this.sideNotes = null
                    }.id.value
            }
        val billId =
            runBlocking {
                GlobalContext
                    .get()
                    .get<BillService>()
                    .createBill(
                        employeeId = closer.id.value.toString(),
                        deviceId = deviceId,
                        request =
                            BillDto.CreateBillRequestDto(
                                tableSessionId = tws.session.id.value,
                                orderItems = listOf(itemId),
                                billAmount = "20.00",
                                tips = tip,
                                payments =
                                    listOf(
                                        PaymentDto.PaymentRequest(
                                            amount = (BigDecimal("20.00") + BigDecimal(tip)).toPlainString(),
                                            paymentTypeId = day.cashPaymentTypeId,
                                        ),
                                    ),
                            ),
                    ).billId
            }
        transaction {
            val session = TableSession.findById(tws.session.id)!!
            val table = RTable.findById(tws.table.id)!!
            session.completed = true
            session.endTime = currentTimeStamp()
            table.currentSession = null
            table.tableStatus = TableStatus.FREE
        }
        return billId
    }
}
