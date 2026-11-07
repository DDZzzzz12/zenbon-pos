package org.example.project.features.bill

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Round-3 INV-F4: a voucher redemption is ONE event carried by TWO DB columns —
 * the tender side ([org.example.project.features.payment.domain.models.Payment.amount],
 * netted into UMS_BRUTTO / transactions_vat) and the Bonpos side
 * ([org.example.project.features.voucher.domain.models.VoucherRedeem.redeemAmount],
 * emitted into lines.csv / lines_vat / businesscases). They are linked only by convention,
 * so before this guard a request pairing a 20.00 voucher payment with a 15.00 redeemedAmount
 * passed every bill guard (paid-total uses Payment.amount only) yet split the two export files.
 *
 * [org.example.project.features.bill.data.service.BillServiceImpl.redeemVoucher] is the single
 * choke point billing routes through, so the equality is enforced there.
 */
@DisplayName("Bill voucher redeemAmount == payment amount (INV-F4)")
class BillVoucherRedeemAmountTest {
    private val deviceId = "voucher-redeem-amount-device"

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    private fun createTerminal() =
        transaction {
            val now = currentTimeStamp()
            Terminal.new {
                this.deviceId = this@BillVoucherRedeemAmountTest.deviceId
                this.clientId = "TERM-VOUCHER-REDEEM"
                this.registeredAt = now
                this.lastSeenAt = now
            }
        }

    /**
     * Seeds a one-item (20.00 @ 19%) session paid by a single voucher tender of [paymentAmount],
     * with the redemption DTO carrying [redeemedAmount], then closes it through the real service.
     * Returns the created bill id.
     */
    private fun runVoucherBill(
        paymentAmount: String,
        redeemedAmount: String,
    ): Long {
        val admin = TestData.createAdmin(employeeNumber = "VRA_ADM", username = "vra_adm")
        val vats = TestData.createDefaultVatRates()
        val vat19 = vats.first()
        val voucherTender = TestData.createPaymentType("Voucher_VRA", isCash = false, isVoucher = true)
        val tws = TestData.createTableWithSession("VRA")
        val category = TestData.createCategory("VRA Cat")
        val product = TestData.createProduct(category.id.value, "VRA Product", "VRA_P", BigDecimal("20.0000"))
        createTerminal()

        val itemId =
            transaction {
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
        return runBlocking {
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
                                    PaymentDto.PaymentRequest(amount = paymentAmount, paymentTypeId = voucherTender.id.value),
                                ),
                            // EXTERNAL source: no internal Voucher row lookup, so the test isolates the
                            // redeemedAmount == payment.amount guard from balance/lookup concerns.
                            voucherRedeem =
                                listOf(
                                    VoucherDto.RedeemVoucherRequestDto(
                                        voucherNumber = "EXT-VRA-1",
                                        redeemedAmount = redeemedAmount,
                                        voucherSource = VoucherSourceType.EXTERNAL,
                                    ),
                                ),
                        ),
                ).billId
        }
    }

    @Test
    fun `voucher redeemedAmount diverging from the payment amount is rejected as 422`() =
        testApp(
            setupData = {
                val ex =
                    assertFailsWith<PosException> {
                        // 20.00 voucher tender, but the redemption claims only 15.00 — this splits
                        // UMS_BRUTTO (nets 20.00) from lines.csv / businesscases (emit 15.00).
                        runVoucherBill(paymentAmount = "20.00", redeemedAmount = "15.00")
                    }
                assertEquals(
                    HttpStatusCode.UnprocessableEntity,
                    ex.httpStatus,
                    "divergent voucher amounts must be a 422 semantic mismatch; got ${ex.httpStatus}",
                )
                assertTrue(
                    ex.message?.contains("15.00") == true && ex.message?.contains("20.00") == true,
                    "message must name both the redeemedAmount (15.00) and the payment amount (20.00); " +
                        "got: ${ex.message}",
                )
            },
        ) { _ -> }

    @Test
    fun `voucher redeemedAmount equal to the payment amount succeeds`() =
        testApp(
            setupData = {
                val billId = runVoucherBill(paymentAmount = "20.00", redeemedAmount = "20.00")

                transaction {
                    val bill = Bill.findById(billId)!!
                    val voucherPayment = bill.payments.single { it.paymentType.isVoucher }
                    val redeem =
                        voucherPayment.voucherRedeem
                            ?: error("expected a VoucherRedeem linked to the voucher payment")
                    assertEquals(
                        0,
                        redeem.redeemAmount.compareTo(voucherPayment.amount),
                        "happy path: VoucherRedeem.redeemAmount (${redeem.redeemAmount}) must equal " +
                            "Payment.amount (${voucherPayment.amount})",
                    )
                    assertEquals(
                        0,
                        redeem.redeemAmount.compareTo(BigDecimal("20.00")),
                        "sanity: redeemAmount should be 20.00; got ${redeem.redeemAmount}",
                    )
                }
            },
        ) { _ -> }
}
