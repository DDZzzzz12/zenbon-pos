package org.example.project.features.zBon

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Service-level regression test for [org.example.project.features.zBon.data.service.ZBonServiceImpl.createReport]
 * covering the Pfand split (spec 2026-07-09 §2.1): a bill carrying a deposit item and a
 * deposit-return item must yield separate PFAND lines on the Z-Bon's aggregate Kassenbuch
 * entry, with the STANDARD line for that VAT rate reduced accordingly and the entry total
 * invariant (sum of lines == net cash amount) preserved.
 *
 * Fixture is deliberately chosen so deposit (+0.25) and return (-0.15) do NOT cancel out,
 * making both the STANDARD reduction and both PFAND lines independently visible:
 *   drink 2.00 + deposit 0.25 + leergut -0.15 = gross 2.10, all cash, all VAT 19%.
 */
@DisplayName("Z-Bon createReport — Pfand split")
class ZBonPfandSplitTest {
    @Test
    fun `z-bon splits deposit and return into PFAND lines and reduces the STANDARD line`() =
        testApp(
            setupData = { setupPfandDay() },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Created, response.status, "body: ${response.bodyAsText()}")

            transaction {
                val header =
                    KassenbuchHeader
                        .all()
                        .orderBy(KassenbuchHeaderTable.id to SortOrder.DESC)
                        .first()
                assertTrue(header.isZbonAggregate)

                val lines = header.lineItems.toList()

                val pfandLines = lines.filter { it.kind == KassenbuchLineKind.PFAND }
                assertEquals(2, pfandLines.size, "expected exactly 2 PFAND lines (deposit + return)")

                val depositLine = pfandLines.first { it.belegtext!!.startsWith("TEPfand") }
                assertEquals(0, BigDecimal("0.25").compareTo(depositLine.vorzBetrag))
                assertEquals("19", depositLine.ustSatz)

                val returnLine = pfandLines.first { it.belegtext!!.startsWith("TELeergut") }
                assertEquals(0, BigDecimal("-0.15").compareTo(returnLine.vorzBetrag))
                assertEquals("19", returnLine.ustSatz)

                val std19 = lines.first { it.kind == KassenbuchLineKind.STANDARD && it.ustSatz == "19" }
                // gross 2.10 - deposit 0.25 - return(-0.15) = 2.00
                assertEquals(0, BigDecimal("2.00").compareTo(std19.vorzBetrag))

                // Entry total invariant: STANDARD 2.00 + deposit 0.25 + return -0.15 = 2.10 (all cash)
                assertEquals(0, BigDecimal("2.10").compareTo(header.totalAmount))
            }
        }

    private fun setupPfandDay() {
        val admin = TestData.createAdmin()
        TestData.createShopInfo()
        val vats = TestData.createDefaultVatRates() // [0]=19% Normal, [1]=7% Reduced
        val cash = TestData.createPaymentType("Cash", isCash = true)
        val tws = TestData.createTableWithSession("PF1")
        val category = TestData.createCategory("Pfand Cat")
        val drink = TestData.createProduct(category.id.value, "Cola", "COLA")
        val pfand = TestData.createProduct(category.id.value, "Pfand Einweg", "PF25")
        val leergut = TestData.createProduct(category.id.value, "Leergut Einweg", "LG25")

        transaction {
            // H2 stand-in for the Postgres advisory lock used to serialize Z-Bon creation.
            exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")

            pfand.isDeposit = true
            leergut.isDepositReturn = true

            val now = currentTimeStamp()

            // Terminal for the token's deviceId (no X-Device-ID header at login => "unknown")
            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TEST"
                registeredAt = now
                lastSeenAt = now
            }

            // Close the session and free the table so Z-Bon validation passes
            val session = TableSession.findById(tws.session.id)!!
            val table = RTable.findById(tws.table.id)!!
            session.completed = true
            session.endTime = now
            table.currentSession = null
            table.tableStatus = TableStatus.FREE

            val vat19 = vats[0]

            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "PFAND-1"
                    this.billAmount = BigDecimal("2.10")
                    this.paidAmount = BigDecimal("2.10")
                    this.tips = BigDecimal("0.00")
                    this.startTime = now
                    this.endTime = now
                    this.isTraining = false
                }

            BillVatDetail.new {
                this.bill = bill
                this.vat = vat19
                this.netAmount = BigDecimal("1.76")
                this.vatAmount = BigDecimal("0.34")
                this.totalAmount = BigDecimal("2.10")
            }

            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("2.10")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }

            fun item(
                product: org.example.project.features.product.domain.models.Product,
                price: String,
            ) = OrderItem.new {
                this.session = session
                this.bill = bill
                this.product = product
                this.unitPrice = BigDecimal(price)
                this.finalPrice = BigDecimal(price)
                this.vat = vat19
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
            }

            item(drink, "2.00")
            item(pfand, "0.25")
            item(leergut, "-0.15")
        }
    }
}
