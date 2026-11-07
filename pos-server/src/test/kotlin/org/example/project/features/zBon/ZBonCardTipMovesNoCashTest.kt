package org.example.project.features.zBon

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
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
import kotlin.test.assertNotNull

/**
 * Integration regression test: a fiscal day whose only bill is tipped and settled ENTIRELY by card.
 * One bill of 100.00 gross tipped 30.00, paid by a single 130.00 card payment — reachable through
 * the production invariant "sum of payments = billAmount - promotions + tips". Nothing at all was
 * tendered in cash, so no cash moved: netCashAmount is 0.00 and the drawer is left untouched.
 *
 * **It read -30.00 before plan 2026-09-16 P1.3, and that was an artifact rather than a fact.** The
 * employee tip was stored as a 0 %-rate `BillVatDetail` row purely so the signed receipt balanced,
 * so `revenueGross` saw only 100.00 while the card leg subtracted the full 130.00 tender — a 30.00
 * phantom cash outflow on a day no coin was touched. P1.1 removed the fake VAT row and P1.3 adds the
 * non-taxable tip back to the derivation explicitly, so the two sides agree again. There is no cash
 * payment anywhere in this fixture (see [setupNegativeNetCashDay]), which is why 0.00 is the answer.
 *
 * Still pins the signed-accumulation Kassenbuch semantics on the REAL Z-Bon path: the legacy
 * "isIncoming ? +abs : -abs" math cannot produce this line sequence (+100.00 turnover, +30.00 tip,
 * -130.00 card tender, netting to zero). Assertions deliberately target the persisted
 * KassenbuchHeader/line balances, not dto.closingBalance.
 *
 * **The class was named `ZBonNegativeNetCashTest` and no longer pins a negative `netCashAmount` —
 * it pins the opposite.** Renamed 2026-09-16 to say what it asserts. Note the consequence: after
 * P1.3 **no test pins a genuinely negative `netCashAmount` through the real Z-Bon path**. A
 * card-paid tip was the documented route to one, and it is now correctly 0.00. A real negative
 * drawer movement needs a different fixture — non-cash tender exceeding cash revenue by some
 * mechanism other than a tip — and none exists yet.
 */
@DisplayName("Z-Bon createReport — a card-paid employee tip moves no cash")
class ZBonCardTipMovesNoCashTest {
    @Test
    fun `a day settled entirely by card leaves the kassenbuch balance unchanged`() =
        testApp(
            setupData = { setupNegativeNetCashDay() },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Created, response.status, "body: ${response.bodyAsText()}")

            transaction {
                val header =
                    assertNotNull(
                        KassenbuchHeader
                            .all()
                            .orderBy(KassenbuchHeaderTable.id to SortOrder.DESC)
                            .firstOrNull(),
                        "Z-Bon should have created a kassenbuch entry",
                    )
                // Nothing was tendered in cash, so the drawer neither gained nor lost.
                assertEquals(0, BigDecimal("0.00").compareTo(header.totalAmount))
                assertEquals(0, BigDecimal("0.00").compareTo(header.currentBalance)) // opening balance 0

                val lines = header.lineItems.sortedBy { it.lineNumber }
                assertEquals(3, lines.size, "19 % turnover, the non-taxable tip, and the card tender")
                // Signed accumulation across all three: +100.00 turnover, +30.00 tip, -130.00 card.
                val expectedSubBalances = listOf("100.00", "130.00", "0.00").map { BigDecimal(it) }
                expectedSubBalances.zip(lines.map { it.subBalance }).forEachIndexed { i, (exp, act) ->
                    assertEquals(0, exp.compareTo(act), "subBalance[$i]: expected $exp, got $act")
                }
            }
        }

    private fun setupNegativeNetCashDay() {
        val admin = TestData.createAdmin()
        TestData.createShopInfo()
        val vats = TestData.createDefaultVatRates() // [0]=19% Normal
        val card = TestData.createPaymentType("Card", isCash = false)
        val tws = TestData.createTableWithSession("ZNC1")

        transaction {
            // H2 stand-in for the Postgres advisory lock used to serialize Z-Bon creation.
            exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")

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

            // Production invariant (BillServiceImpl): sum(payments) = billAmount - promotions + tips
            // => 130.00 = 100.00 - 0.00 + 30.00, all on ONE card payment.
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "ZNC-1"
                    this.billAmount = BigDecimal("100.00")
                    this.paidAmount = BigDecimal("130.00")
                    this.tips = BigDecimal("30.00")
                    this.startTime = now
                    this.endTime = now
                }

            BillVatDetail.new {
                this.bill = bill
                this.vat = vats[0]
                this.netAmount = BigDecimal("84.03")
                this.vatAmount = BigDecimal("15.97")
                this.totalAmount = BigDecimal("100.00")
            }

            Payment.new {
                this.bill = bill
                this.paymentType = card
                this.amount = BigDecimal("130.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
        }
    }
}
