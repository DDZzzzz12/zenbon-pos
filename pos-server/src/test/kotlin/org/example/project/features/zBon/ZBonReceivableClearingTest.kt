package org.example.project.features.zBon

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.zBon.database.tables.ZBonTable
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Slice 4c-2c: a closing that contains a receivable **cleared at the till** alongside an ordinary
 * sale. The clearing is money for a sale already booked, so it must not be counted as revenue again,
 * and the drawer derivation cannot express it on its own — the fix is three explicit terms.
 *
 * | Clearing | totalPosRevenue | drawer (aggregate CASH_SALE) |
 * |----------|-----------------|------------------------------|
 * | cash 23  | 100.00          | 123.00                       |
 * | card 23  | 100.00          | 100.00                       |
 *
 * The card row is the one the old derivation broke: without the exclusion it would subtract the
 * card leg and read 77.00 — a phantom shortfall every closing.
 */
@DisplayName("Z-Bon createReport — a receivable cleared at the till (4c-2c)")
class ZBonReceivableClearingTest {
    @Test
    fun `a cash clearing raises the drawer without becoming revenue`() =
        testApp(setupData = { setupDay(cashClearing = true) }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Created, response.status, "body: ${response.bodyAsText()}")

            transaction {
                val report = latestReport()
                assertEquals(0, BigDecimal("100.00").compareTo(report.totalPosRevenue), "the clearing is not revenue")
                assertEquals(0, BigDecimal("23.00").compareTo(report.totalReceivableCleared), "23.00 was cleared")
                assertEquals(0, BigDecimal("123.00").compareTo(aggregateDrawer()), "the cash clearing is in the drawer")
            }
        }

    @Test
    fun `a card clearing changes neither revenue nor the drawer`() =
        testApp(setupData = { setupDay(cashClearing = false) }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Created, response.status, "body: ${response.bodyAsText()}")

            transaction {
                val report = latestReport()
                assertEquals(0, BigDecimal("100.00").compareTo(report.totalPosRevenue), "the clearing is not revenue")
                assertEquals(0, BigDecimal("23.00").compareTo(report.totalReceivableCleared), "23.00 was cleared")
                // The whole point: a card clearing is NOT subtracted as if it were non-cash revenue.
                assertEquals(0, BigDecimal("100.00").compareTo(aggregateDrawer()), "no phantom 77.00 shortfall")
            }
        }

    private fun latestReport(): ZBon =
        assertNotNull(
            ZBon.all().orderBy(ZBonTable.id to SortOrder.DESC).firstOrNull(),
            "a Z-report should have been created",
        )

    /** The takings booked into the fiscal cash book — the aggregate CASH_SALE entry's signed total. */
    private fun aggregateDrawer(): BigDecimal =
        assertNotNull(
            KassenbuchHeader
                .find { KassenbuchHeaderTable.isZbonAggregate eq true }
                .orderBy(KassenbuchHeaderTable.id to SortOrder.DESC)
                .firstOrNull(),
            "the Z-Bon should have booked its aggregate cash entry",
        ).totalAmount

    /** One ordinary 100.00 cash sale plus one 23.00 clearing settled by cash or card. */
    private fun setupDay(cashClearing: Boolean) {
        val admin = TestData.createAdmin()
        val shop = TestData.createShopInfo()
        val vats = TestData.createDefaultVatRates() // [0]=19% Normal
        val cash = TestData.createPaymentType("Bar", isCash = true)
        val clearingTender =
            if (cashClearing) cash else TestData.createPaymentType("Karte", isCash = false)
        val tws = TestData.createTableWithSession("ZRC1")

        transaction {
            exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TEST"
                registeredAt = now
                lastSeenAt = now
            }

            val session = TableSession.findById(tws.session.id)!!
            val table = RTable.findById(tws.table.id)!!
            session.completed = true
            session.endTime = now
            table.currentSession = null
            table.tableStatus = TableStatus.FREE

            // Ordinary sale: 100.00 @19%, paid cash.
            val saleBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "ZRC-SALE"
                    this.billAmount = BigDecimal("100.00")
                    this.paidAmount = BigDecimal("100.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                }
            BillVatDetail.new {
                this.bill = saleBill
                this.vat = vats[0]
                this.netAmount = BigDecimal("84.03")
                this.vatAmount = BigDecimal("15.97")
                this.totalAmount = BigDecimal("100.00")
            }
            Payment.new {
                this.bill = saleBill
                this.paymentType = cash
                this.amount = BigDecimal("100.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }

            // An issued receivable, opened on some earlier Vorgang, cleared here at the counter.
            val invoice =
                SalesInvoice.new {
                    this.shop = shop
                    this.revenueOrigin = RevenueOrigin.POS_RECEIVABLE
                    this.status = SalesInvoiceStatus.ISSUED
                    this.buyerName = "Catering Nord GmbH"
                    this.issueDate = now
                    this.serviceDate = now
                    this.billId = saleBill.id
                }

            val clearingBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "ZRC-CLR"
                    this.billAmount = BigDecimal("23.00")
                    this.paidAmount = BigDecimal("23.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.clearsSalesInvoiceId = invoice.id.value
                }
            Payment.new {
                this.bill = clearingBill
                this.paymentType = clearingTender
                this.amount = BigDecimal("23.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
                this.createsReceivable = false
            }
        }
    }
}
