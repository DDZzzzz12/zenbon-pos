package org.example.project.features.zBon

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NEW-25 (adminApp UI review, merged pass): a Z-Bon whose period would overlap an existing closing
 * of the shop is a business refusal. The repository's overlap guard used to escape as a bare
 * `IllegalStateException` and the route answered **500 INTERNAL_ERROR**; it now answers a
 * **409 `ZBON_PERIOD_OVERLAP`** problem, and nothing is written (no third Z-Bon, no Z-number used).
 *
 * The overlap is produced the way the QA seed produced it: an older closing (lower id) whose end
 * time lies after the end time of the newest closing, which is where the next period would start.
 */
class ZBonPeriodOverlapTest {
    private val deviceId = "zbon-period-overlap-device"

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    @Test
    fun `an overlapping period answers 409 ZBON_PERIOD_OVERLAP and writes nothing`() =
        testApp(setupData = {
            setupPaidDay()
            seedInconsistentClosings()
        }) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)

            val response = client.post("/api/z-bon") { bearerAuth(token) }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.Conflict, response.status, body)
            assertTrue(body.contains(PosErrorCode.ZBON_PERIOD_OVERLAP.name), body)
            transaction {
                assertEquals(2L, ZBon.count(), "a refused closing must not create a Z-Bon")
            }
        }

    /** Z-Bon 1 ends tomorrow, Z-Bon 2 (the newest row) ended yesterday: the next period starts yesterday. */
    private fun seedInconsistentClosings() {
        transaction {
            val shopInfo = ShopInfo.all().first()
            val employee = Employee.all().first()
            val now = currentTimeStamp()
            newClosing(shopInfo, employee, number = 1L, end = now.plusDays(1))
            newClosing(shopInfo, employee, number = 2L, end = now.plusDays(-1))
        }
    }

    private fun newClosing(
        shopInfo: ShopInfo,
        employee: Employee,
        number: Long,
        end: LocalDateTime,
    ) {
        ZBon.new {
            this.shopInfo = shopInfo
            this.employee = employee
            this.startTime = end.plusDays(-1)
            this.endTime = end
            this.cancelledAmount = BigDecimal.ZERO
            this.totalDiscounts = BigDecimal.ZERO
            this.totalPosRevenue = BigDecimal.ZERO
            this.adminTip = BigDecimal.ZERO
            this.openingBalance = BigDecimal.ZERO
            this.expenses = BigDecimal.ZERO
            this.cashRevenue = BigDecimal.ZERO
            this.closingBalance = BigDecimal.ZERO
            this.createdAt = end
            this.trainingRevenue = BigDecimal.ZERO
            this.trainingBillCount = 0
            this.refundAmount = BigDecimal.ZERO
            this.stornoBillCount = 0
            this.zbonNummer = number
        }
    }

    private fun LocalDateTime.plusDays(days: Long): LocalDateTime = toJavaLocalDateTime().plusDays(days).toKotlinLocalDateTime()

    /** One paid 20.00 cash bill (copied from [ZBonAbandonedSessionTest]), so there is business to close. */
    private fun setupPaidDay() {
        TestData.createShopInfo()
        TestData.createAdmin(employeeNumber = "ZPO_ADM", username = "admin")
        val closer = TestData.createManager(employeeNumber = "ZPO_MGR", username = "zpo_mgr")
        val vat19 = TestData.createDefaultVatRates().first()
        val cash = TestData.createPaymentType("Cash_ZPO", isCash = true)
        val tws = TestData.createTableWithSession("ZPO1")
        val category = TestData.createCategory("ZPO Cat")
        val product = TestData.createProduct(category.id.value, "ZPO Product", "ZPO_P", BigDecimal("20.0000"))
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TERM-ZPO")

        val itemId =
            transaction {
                // H2 stand-in for the Postgres advisory lock used to serialize Z-Bon creation.
                exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")

                val session = TableSession.findById(tws.session.id)!!
                session.employeeId = closer
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
                            payments =
                                listOf(
                                    PaymentDto.PaymentRequest(amount = "20.00", paymentTypeId = cash.id.value),
                                ),
                        ),
                )
        }

        transaction {
            val session = TableSession.findById(tws.session.id)!!
            val table = RTable.findById(tws.table.id)!!
            session.completed = true
            session.endTime = currentTimeStamp()
            table.currentSession = null
            table.tableStatus = TableStatus.FREE
        }
    }
}
