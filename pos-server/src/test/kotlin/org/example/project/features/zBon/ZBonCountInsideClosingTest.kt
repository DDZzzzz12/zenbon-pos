package org.example.project.features.zBon

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2E-2026-09-18 #8: the till recorded the drawer count (a TSE-signed Kassendifferenz) and only then
 * asked for the Z-Bon, so a refused closing left the difference booked outside any closing. The count
 * now travels in the Z-Bon request and is booked only after the closing's preconditions pass.
 */
class ZBonCountInsideClosingTest {
    private val deviceId = "zbon-count-inside-device"

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    private fun shortages() = KassenbuchHeader.find { KassenbuchHeaderTable.entryType eq KassenbuchEntryType.CASH_SHORTAGE }.count()

    @Test
    fun `a refused closing books no Kassendifferenz`() =
        testApp(setupData = {
            setupPaidDay()
            val t = TestData.createTableWithSession("ZCC2")
            TestData.createOrderItemInSession(t.session, ItemStatus.OPEN)
        }) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            val entriesBefore = transaction { KassenbuchHeader.count() }

            val response =
                client.post("/api/z-bon") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"countedAmount":"1.00"}""") // far below the drawer: would book a shortage
                }

            assertTrue(response.status.value in 400..499, response.bodyAsText())
            transaction { assertEquals(entriesBefore, KassenbuchHeader.count(), "no Kassendifferenz may be booked") }
        }

    @Test
    fun `an accepted closing books the difference and closes`() =
        testApp(setupData = { setupPaidDay() }) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)

            val response =
                client.post("/api/z-bon") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"countedAmount":"18.00"}""") // the drawer holds 23.00
                }

            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            transaction {
                val shortage =
                    assertNotNull(
                        KassenbuchHeader.find { KassenbuchHeaderTable.entryType eq KassenbuchEntryType.CASH_SHORTAGE }.firstOrNull(),
                        "the counted 5.00 shortfall is booked",
                    )
                assertEquals(0, BigDecimal("-5.00").compareTo(shortage.totalAmount), "got ${shortage.totalAmount}")
            }
        }

    @Test
    fun `no body still closes exactly as before and books no count`() =
        testApp(setupData = { setupPaidDay() }) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)

            val response = client.post("/api/z-bon") { bearerAuth(token) }

            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            transaction { assertEquals(0L, shortages()) }
        }

    /**
     * One 20.00 item closed by a MANAGER with a 3.00 tip, fully tendered in cash, through the real
     * [BillService] (copied from `ZBonEmployeeTipLineTest.setupTippedDay(closeAsAdmin = false)`), so
     * the closing has open business to close. The session is then closed and the table freed.
     */
    private fun setupPaidDay() {
        TestData.createShopInfo()
        TestData.createAdmin(employeeNumber = "ZCC_ADM", username = "admin")
        val closer = TestData.createManager(employeeNumber = "ZCC_MGR", username = "zcc_mgr")
        val vat19 = TestData.createDefaultVatRates().first()
        val cash = TestData.createPaymentType("Cash_ZCC", isCash = true)
        val tws = TestData.createTableWithSession("ZCC1")
        val category = TestData.createCategory("ZCC Cat")
        val product = TestData.createProduct(category.id.value, "ZCC Product", "ZCC_P", BigDecimal("20.0000"))
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TERM-ZCC")

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
