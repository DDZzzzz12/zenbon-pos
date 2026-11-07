package org.example.project.features.zBon

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.voucher.domain.models.Voucher
import org.example.project.features.voucher.domain.models.VoucherRedeem
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression coverage for two Z-Bon correctness fixes:
 *
 * 1. A voucher redemption reversed by a storno (deleteBill stamps [VoucherRedeem.reversedByBill])
 *    must NOT show up on the Z-Bon — the rest of the report already excludes storno'd bills.
 *
 * 2. A storno'd sale leaves the Z-Bon's payment summaries: neither the original's payment nor the
 *    reversal may skew the day's payment-type totals.
 */
@DisplayName("Z-Bon — voucher redemption reversal + storno")
class ZBonVoucherRedeemReversalTest {
    private fun closeSessionAndFreeTable(
        sessionId: Long,
        tableId: Long,
    ) {
        transaction {
            val now = currentTimeStamp()
            val session = TableSession.findById(sessionId)!!
            val table = RTable.findById(tableId)!!
            session.completed = true
            session.endTime = now
            table.currentSession = null
            table.tableStatus = TableStatus.FREE
        }
    }

    @Test
    fun `redemption reversed by storno is not listed on the Z-Bon`() =
        testApp(
            setupData = { setupVoucherStornoReversed() },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Created, response.status, "body: ${response.bodyAsText()}")

            val dto = testJson.decodeFromString<ZBonDto.ZBonResponseDto>(response.bodyAsText())
            assertTrue(dto.voucherRedeems.isNullOrEmpty(), "reversed redemption must not appear: ${dto.voucherRedeems}")
        }

    private fun setupVoucherStornoReversed() {
        val admin = TestData.createAdmin()
        TestData.createShopInfo()
        val vats = TestData.createDefaultVatRates()
        val cash = TestData.createPaymentType("Cash", isCash = true)
        val voucherType = TestData.createPaymentType("Voucher", isCash = false, isVoucher = true)
        val tws1 = TestData.createTableWithSession("VR1")
        val tws2 = TestData.createTableWithSession("VR2")
        val category = TestData.createCategory("Voucher Redeem Cat")
        val product = TestData.createProduct(category.id.value, "Voucher Redeem Product", "VRP001")

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

            // Close the sessions and free the tables so Z-Bon validation passes
            listOf(tws1, tws2).forEach { tws ->
                val session = TableSession.findById(tws.session.id)!!
                val table = RTable.findById(tws.table.id)!!
                session.completed = true
                session.endTime = now
                table.currentSession = null
                table.tableStatus = TableStatus.FREE
            }
            val session1 = TableSession.findById(tws1.session.id)!!
            val session2 = TableSession.findById(tws2.session.id)!!
            val vat19 = vats[0]

            // Normal cash bill so the report has active content
            val normalBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session1
                    this.billNumber = "VR-NORMAL"
                    this.billAmount = BigDecimal("50.00")
                    this.paidAmount = BigDecimal("50.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                }
            Payment.new {
                this.bill = normalBill
                this.paymentType = cash
                this.amount = BigDecimal("50.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }

            // Voucher-paid bill that was storno'd — this is the state deleteBill produces:
            // reverse bill created, original stamped deletingReason, voucher payment REFUNDED,
            // and (the fix under test) the VoucherRedeem stamped reversedByBill.
            val voucherBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session2
                    this.billNumber = "VR-ORIG"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                }
            val reverseBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session2
                    this.billNumber = "VR-REV"
                    this.billAmount = BigDecimal("-10.00")
                    this.paidAmount = BigDecimal("-10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.deletedBill = voucherBill
                }
            voucherBill.deletingReason = "VR-REV : storno test"

            val voucherPayment =
                Payment.new {
                    this.bill = voucherBill
                    this.paymentType = voucherType
                    this.amount = BigDecimal("10.00")
                    this.currency = "EUR"
                    this.paymentStatus = PaymentStatus.REFUNDED
                    this.paymentTime = now
                    this.refundTime = now
                    this.refundedBillId = reverseBill
                }

            val voucherItem =
                OrderItem.new {
                    this.session = session2
                    this.product = product
                    this.bill = voucherBill
                    this.unitPrice = BigDecimal("25.00")
                    this.finalPrice = BigDecimal("25.00")
                    this.itemStatus = ItemStatus.PAID
                    this.vat = vat19
                    this.takeAway = false
                }
            val voucher =
                Voucher.new {
                    this.orderItem = voucherItem
                    this.voucherNumber = "V-REV-1"
                    this.originalValue = BigDecimal("25.00")
                    this.remainingValue = BigDecimal("25.00")
                    this.createdAt = now
                }
            VoucherRedeem.new {
                this.voucher = voucher
                this.payment = voucherPayment
                this.redeemAmount = BigDecimal("10.00")
                this.redeemedAt = now
                this.voucherType = VoucherSourceType.INTERNAL
                this.voucherNumber = "V-REV-1"
                this.reversedByBill = reverseBill
            }
        }
    }

    /** Ids created by [setupOpenCashBillForStorno], readable inside the test body. */
    private data class StornoFixture(
        val billId: Long,
        val cashTypeId: Long,
        val cardTypeId: Long,
        val sessionId: Long,
        val tableId: Long,
    )

    private var stornoFixture: StornoFixture? = null

    /** A second, independent normal cash bill so the report keeps active content after a storno. */
    private data class SecondBillFixture(
        val sessionId: Long,
        val tableId: Long,
    )

    private var secondBillFixture: SecondBillFixture? = null

    /** Device identity for the terminal-capability ceiling — matches the fiscal+registered
     * Terminal seeded below, so DELETE /api/v1/bills/{id} clears it. */
    private val stornoDeviceId = "zbon-storno-device"

    private fun setupOpenCashBillForStorno() {
        val admin = TestData.createAdmin()
        TestData.createShopInfo()
        val cash = TestData.createPaymentType("Cash", isCash = true)
        val card = TestData.createPaymentType("Card", isCash = false)
        val tws = TestData.createTableWithSession("PC1")
        val twsOther = TestData.createTableWithSession("PC2")
        TestData.createFiscalRegisteredTerminal(deviceId = stornoDeviceId, clientId = "TERM-TEST-PC1")

        transaction {
            exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")
            val now = currentTimeStamp()

            val session = TableSession.findById(tws.session.id)!!
            val table = RTable.findById(tws.table.id)!!
            session.completed = true
            session.endTime = now
            table.currentSession = null
            table.tableStatus = TableStatus.FREE

            val otherSession = TableSession.findById(twsOther.session.id)!!
            val otherTable = RTable.findById(twsOther.table.id)!!
            otherSession.completed = true
            otherSession.endTime = now
            otherTable.currentSession = null
            otherTable.tableStatus = TableStatus.FREE

            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "PC-1"
                    this.billAmount = BigDecimal("30.00")
                    this.paidAmount = BigDecimal("30.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                }
            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("30.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }

            // A second, independent cash bill so the report always has active content, even
            // after the first bill is reversed by a storno.
            val secondBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = otherSession
                    this.billNumber = "PC-2"
                    this.billAmount = BigDecimal("15.00")
                    this.paidAmount = BigDecimal("15.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                }
            Payment.new {
                this.bill = secondBill
                this.paymentType = cash
                this.amount = BigDecimal("15.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }

            stornoFixture =
                StornoFixture(
                    billId = bill.id.value,
                    cashTypeId = cash.id.value,
                    cardTypeId = card.id.value,
                    sessionId = session.id.value,
                    tableId = table.id.value,
                )
            secondBillFixture = SecondBillFixture(otherSession.id.value, otherTable.id.value)
        }
    }

    @Test
    fun `storno removes the sale from Z-Bon payment summaries`() =
        testApp(
            setupData = { setupOpenCashBillForStorno() },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = stornoDeviceId)
            val f = stornoFixture!!

            val deleteResponse = client.delete("/api/v1/bills/${f.billId}?reason=test") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, deleteResponse.status, "body: ${deleteResponse.bodyAsText()}")

            // Storno of a non-quick-sale bill re-opens the table session for re-billing — close it
            // again so the day can be closed by a Z-Bon, exactly as an operator would in practice.
            closeSessionAndFreeTable(f.sessionId, f.tableId)

            val zBonResponse = client.post("/api/z-bon") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Created, zBonResponse.status, "body: ${zBonResponse.bodyAsText()}")

            val dto = testJson.decodeFromString<ZBonDto.ZBonResponseDto>(zBonResponse.bodyAsText())
            val payments = assertNotNull(dto.paymentSummary)

            // The whole sale was reversed: the original bill's cash payment may not contribute. Only
            // the second, untouched bill's cash payment (15.00) remains.
            val cashSummary = payments.find { it.paymentName == "Cash" }
            assertEquals("15.00", cashSummary?.amount, "body: ${zBonResponse.bodyAsText()}")
            val cardSummary = payments.find { it.paymentName == "Card" }
            assertTrue(cardSummary == null, "Card summary must not appear: $cardSummary, body: ${zBonResponse.bodyAsText()}")

            assertEquals("30.00", dto.refundAmount, "body: ${zBonResponse.bodyAsText()}")
        }
}
