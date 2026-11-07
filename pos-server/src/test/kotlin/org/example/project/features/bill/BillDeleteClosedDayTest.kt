package org.example.project.features.bill

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round-3 KABS-1: DELETE /api/v1/bills/{id} (storno) must reject a bill whose business day is
 * already closed (zBon != null). Once the closing is
 * exported the bill's data is frozen; a storno would rewrite the closed closing's businesscases and
 * its cash refund would never reach the current closing's balance.
 */
@DisplayName("deleteBill closed-day guard (round-3 KABS-1)")
class BillDeleteClosedDayTest {
    // Device identity for the terminal-capability ceiling in RouteAuthorizationPlugin — every
    // DELETE /api/v1/bills/{id} call in this file logs in with this deviceId, matching the
    // fiscal+registered Terminal seeded by seedPaidCashBill.
    private val deviceId = "delete-closed-day-device"

    // BillNumberTracker is a JVM-global singleton that survives across tests; deleteBill reserves a
    // reverse-bill number and only releases it on the failure path. Clear after each test to keep
    // this class hermetic (see BillChangePaymentTest for the full rationale). @AfterEach only.
    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    private data class Fixture(
        val billId: Long,
    )

    private var fixture: Fixture? = null

    /** Seeds one bill paid fully in cash with endTime set — the storno-eligible baseline. */
    private fun seedPaidCashBill(amount: String = "30.00") {
        val admin = TestData.createAdmin()
        TestData.createShopInfo()
        val cash = TestData.createPaymentType("Cash", isCash = true)
        val tws = TestData.createTableWithSession("DCD1")
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TERM-TEST-DCD1")

        transaction {
            val now = currentTimeStamp()
            val session = TableSession.findById(tws.session.id)!!
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "DCD-1"
                    this.billAmount = BigDecimal(amount)
                    this.paidAmount = BigDecimal(amount)
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                }
            Payment.new {
                this.bill = bill
                this.paymentType = PaymentType.findById(cash.id.value)!!
                this.amount = BigDecimal(amount)
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
            fixture = Fixture(bill.id.value)
        }
    }

    private fun closeBusinessDay(billId: Long) {
        transaction {
            val bill = Bill.findById(billId)!!
            val shopInfo = ShopInfo.all().first()
            val now = currentTimeStamp()
            val zBon =
                ZBon.new {
                    this.shopInfo = shopInfo
                    this.employee = bill.employeeId
                    this.startTime = now
                    this.endTime = now
                    this.cancelledAmount = BigDecimal.ZERO
                    this.totalDiscounts = BigDecimal.ZERO
                    this.totalPosRevenue = BigDecimal.ZERO
                    this.adminTip = BigDecimal.ZERO
                    this.openingBalance = BigDecimal.ZERO
                    this.expenses = BigDecimal.ZERO
                    this.cashRevenue = BigDecimal.ZERO
                    this.closingBalance = BigDecimal.ZERO
                    this.createdAt = now
                    this.trainingRevenue = BigDecimal.ZERO
                    this.trainingBillCount = 0
                    this.refundAmount = BigDecimal.ZERO
                    this.stornoBillCount = 0
                    this.zbonNummer = 7L
                }
            bill.zBon = zBon
        }
    }

    @Test
    fun `storno of a bill whose business day is closed is rejected with 409`() =
        testApp(
            setupData = { seedPaidCashBill() },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            val f = fixture!!
            closeBusinessDay(f.billId)

            val response = client.delete("/api/v1/bills/${f.billId}?reason=test") { bearerAuth(token) }
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.Conflict, response.status, "body: $body")
            assertTrue(body.contains("already closed"), "guard must name the closed business day; body: $body")

            transaction {
                // The bill must be untouched — no reverse bill, no deletingReason.
                assertTrue(Bill.findById(f.billId)!!.deletingReason == null)
                assertTrue(Bill.find { BillTable.deletedBillId eq f.billId }.empty())
            }
        }

    @Test
    fun `storno of an open-day bill still works`() =
        testApp(
            setupData = { seedPaidCashBill() },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            val f = fixture!!

            val response = client.delete("/api/v1/bills/${f.billId}?reason=test") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")

            transaction {
                assertNotNull(Bill.findById(f.billId)!!.deletingReason)
            }
        }

    /**
     * Storno is a TSE-signed, DSFinV-K-exported reversal. Before the registry fix, DELETE
     * /api/v1/bills/{id} was registered as `Authenticated`, so ANY principal holding a valid JWT
     * could execute one — the manager restriction existed only in the client overlay. These tests
     * pin the server-side rule: storno is manager+ only.
     */
    @Test
    fun `cashier is forbidden from stornoing a bill`() =
        testApp(
            setupData = {
                seedPaidCashBill()
                TestData.createCashier()
            },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "cashier", deviceId = deviceId)
            val f = fixture!!

            val response = client.delete("/api/v1/bills/${f.billId}?reason=test") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Forbidden, response.status, "body: ${response.bodyAsText()}")

            transaction {
                // No reversal may have happened.
                assertTrue(Bill.findById(f.billId)!!.deletingReason == null)
                assertTrue(Bill.find { BillTable.deletedBillId eq f.billId }.empty())
            }
        }

    @Test
    fun `terminal is forbidden from stornoing a bill`() =
        testApp(
            setupData = {
                seedPaidCashBill()
                TestData.createTerminal()
            },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "terminal", deviceId = deviceId)
            val f = fixture!!

            val response = client.delete("/api/v1/bills/${f.billId}?reason=test") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Forbidden, response.status, "body: ${response.bodyAsText()}")

            transaction {
                assertTrue(Bill.findById(f.billId)!!.deletingReason == null)
                assertTrue(Bill.find { BillTable.deletedBillId eq f.billId }.empty())
            }
        }

    /** The fix must not be over-tight: MANAGER keeps storno. */
    @Test
    fun `manager can storno a bill`() =
        testApp(
            setupData = {
                seedPaidCashBill()
                TestData.createManager()
            },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "manager", deviceId = deviceId)
            val f = fixture!!

            val response = client.delete("/api/v1/bills/${f.billId}?reason=test") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status, "body: ${response.bodyAsText()}")

            transaction {
                assertNotNull(Bill.findById(f.billId)!!.deletingReason)
            }
        }
}
