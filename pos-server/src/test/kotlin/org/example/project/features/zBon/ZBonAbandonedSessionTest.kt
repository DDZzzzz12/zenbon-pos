package org.example.project.features.zBon

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TableWithSession
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
 * E2E-2026-09-18 #3/#4: a session with nothing OPEN on it (opened but never ordered, every item
 * cancelled, left behind by an old till) is ended by the Z-Bon instead of refusing it. A session with
 * an OPEN item still blocks the closing.
 */
class ZBonAbandonedSessionTest {
    private val deviceId = "zbon-abandoned-session-device"
    private lateinit var abandoned: TableWithSession

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    @Test
    fun `an item-less open session no longer blocks the Z-Bon and is ended by it`() =
        testApp(setupData = {
            setupPaidDay()
            abandoned = TestData.createTableWithSession("ZAS2")
        }) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)

            val response = client.post("/api/z-bon") { bearerAuth(token) }

            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            transaction {
                assertTrue(TableSession.findById(abandoned.session.id.value)!!.completed)
                assertEquals(TableStatus.FREE, RTable.findById(abandoned.table.id.value)!!.tableStatus)
            }
        }

    @Test
    fun `a session with an OPEN item still blocks the Z-Bon`() =
        testApp(setupData = {
            setupPaidDay()
            val t = TestData.createTableWithSession("ZAS3")
            TestData.createOrderItemInSession(t.session, ItemStatus.OPEN)
        }) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)

            val response = client.post("/api/z-bon") { bearerAuth(token) }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("ZBON_TABLES_OPEN"))
            assertTrue(response.bodyAsText().contains("occupied by open items"))
        }

    /**
     * One 20.00 item closed by a MANAGER with a 3.00 tip, fully tendered in cash, through the real
     * [BillService] (copied from `ZBonEmployeeTipLineTest.setupTippedDay(closeAsAdmin = false)`), so
     * the closing has open business to close. The session is then closed and the table freed.
     */
    private fun setupPaidDay() {
        TestData.createShopInfo()
        TestData.createAdmin(employeeNumber = "ZAS_ADM", username = "admin")
        val closer = TestData.createManager(employeeNumber = "ZAS_MGR", username = "zas_mgr")
        val vat19 = TestData.createDefaultVatRates().first()
        val cash = TestData.createPaymentType("Cash_ZAS", isCash = true)
        val tws = TestData.createTableWithSession("ZAS1")
        val category = TestData.createCategory("ZAS Cat")
        val product = TestData.createProduct(category.id.value, "ZAS Product", "ZAS_P", BigDecimal("20.0000"))
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TERM-ZAS")

        val itemId =
            transaction {
                // H2 stand-in for the Postgres advisory lock used to serialize Z-Bon creation.
                exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")

                val session = TableSession.findById(tws.session.id)!!
                // Authorize the closer as the session owner.
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
                            tips = "3.00",
                            payments =
                                listOf(
                                    PaymentDto.PaymentRequest(amount = "23.00", paymentTypeId = cash.id.value),
                                ),
                        ),
                )
        }

        transaction {
            val now = currentTimeStamp()
            val session = TableSession.findById(tws.session.id)!!
            val table = RTable.findById(tws.table.id)!!
            session.completed = true
            session.endTime = now
            table.currentSession = null
            table.tableStatus = TableStatus.FREE
        }
    }
}
