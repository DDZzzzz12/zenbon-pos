package org.example.project.features.zBon

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Payment
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
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the reported defect: a bill paid with a LEGACY (non-internal) voucher is
 * storno'd, yet the redemption still appears in the Z-Bon's "redeemed vouchers" section.
 *
 * [ZBonVoucherRedeemReversalTest] already covers the Z-Bon *query* side, but it hand-writes
 * `VoucherRedeem.reversedByBill` into its fixture. That leaves the producer unverified: nothing
 * asserts that the real `deleteBill` actually stamps the reversal for a redemption whose
 * `voucherSource` is not INTERNAL (LEGACY/EXTERNAL redemptions carry no `Voucher` row at all).
 * This test drives the real createBill → deleteBill → Z-Bon path instead.
 */
@DisplayName("Z-Bon — LEGACY voucher redemption reversed by storno (end-to-end)")
class ZBonLegacyVoucherStornoTest {
    private val deviceId = "zbon-legacy-voucher-storno-device"

    /** Ids produced by [setupLegacyVoucherBill], read inside the test body. */
    private data class Fixture(
        val billId: Long,
        val sessionId: Long,
        val tableId: Long,
    )

    private var fixture: Fixture? = null

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

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
    fun `LEGACY redemption reversed by storno is not listed on the Z-Bon`() =
        testApp(
            setupData = { setupLegacyVoucherBill() },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            val f = assertNotNull(fixture)

            val deleteResponse = client.delete("/api/v1/bills/${f.billId}?reason=legacy voucher storno") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, deleteResponse.status, "body: ${deleteResponse.bodyAsText()}")

            // The storno re-opens the session for re-billing — close it again so the day is closable.
            closeSessionAndFreeTable(f.sessionId, f.tableId)

            val zBonResponse = client.post("/api/z-bon") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Created, zBonResponse.status, "body: ${zBonResponse.bodyAsText()}")

            val dto = testJson.decodeFromString<ZBonDto.ZBonResponseDto>(zBonResponse.bodyAsText())
            assertTrue(
                dto.voucherRedeems.isNullOrEmpty(),
                "storno'd LEGACY redemption must not appear on the Z-Bon: ${dto.voucherRedeems}",
            )
        }

    private fun setupLegacyVoucherBill() {
        val admin = TestData.createAdmin(employeeNumber = "LVS_ADM", username = "admin")
        TestData.createShopInfo()
        val vats = TestData.createDefaultVatRates()
        val vat19 = vats.first()
        val cash = TestData.createPaymentType("Cash_LVS", isCash = true)
        val voucherTender = TestData.createPaymentType("Voucher_LVS", isCash = false, isVoucher = true)
        val tws = TestData.createTableWithSession("LVS1")
        val twsOther = TestData.createTableWithSession("LVS2")
        val category = TestData.createCategory("LVS Cat")
        val product = TestData.createProduct(category.id.value, "LVS Product", "LVS_P", BigDecimal("20.0000"))
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TERM-LVS")

        val itemId =
            transaction {
                // H2 stand-in for the Postgres advisory lock used to serialize Z-Bon creation.
                exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")

                val now = currentTimeStamp()

                // A second, independent cash bill so the closing still has active content after the storno.
                val otherSession = TableSession.findById(twsOther.session.id)!!
                val otherTable = RTable.findById(twsOther.table.id)!!
                otherSession.completed = true
                otherSession.endTime = now
                otherTable.currentSession = null
                otherTable.tableStatus = TableStatus.FREE

                val secondBill =
                    Bill.new {
                        this.employeeId = admin
                        this.tableSession = otherSession
                        this.billNumber = "LVS-OTHER"
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

        val billService = GlobalContext.get().get<BillService>()
        val billId =
            runBlocking {
                billService
                    .createBill(
                        employeeId = admin.id.value.toString(),
                        deviceId = deviceId,
                        request =
                            BillDto.CreateBillRequestDto(
                                tableSessionId = tws.session.id.value,
                                orderItems = listOf(itemId),
                                billAmount = "20.00",
                                tips = "0.00",
                                payments =
                                    listOf(
                                        PaymentDto.PaymentRequest(amount = "20.00", paymentTypeId = voucherTender.id.value),
                                    ),
                                voucherRedeem =
                                    listOf(
                                        VoucherDto.RedeemVoucherRequestDto(
                                            voucherNumber = "ALT-LVS-1",
                                            redeemedAmount = "20.00",
                                            voucherSource = VoucherSourceType.LEGACY,
                                        ),
                                    ),
                            ),
                    ).billId
            }

        fixture = Fixture(billId = billId, sessionId = tws.session.id.value, tableId = tws.table.id.value)
    }
}
