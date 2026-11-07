package org.example.project.features.bill

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.voucher.domain.models.Voucher
import org.example.project.features.voucher.domain.models.VoucherRedeem
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
import kotlin.test.assertNull

/**
 * A Storno of a bill paid by an INTERNAL voucher, through the real `DELETE /api/v1/bills/{id}`:
 * the voucher balance is restored exactly once and the redemption is stamped `reversedByBill` with
 * the reverse bill — the stamp the Z-Bon and the DSFinV-K export read.
 */
@DisplayName("Storno of a voucher-paid bill restores the voucher once")
class BillStornoVoucherRestoreTest {
    // Matches the fiscal+registered Terminal seeded below, so DELETE /api/v1/bills/{id} clears the
    // terminal-capability ceiling.
    private val deviceId = "storno-voucher-restore-device"

    // BillNumberTracker is a JVM-global singleton; deleteBill reserves a reverse-bill number that is
    // only released on failure, so a leaked reservation would collide with the next test.
    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    /** Ids created by [setupBillPaidByVoucher], readable inside the test body. */
    private data class VoucherFixture(
        val billId: Long,
        val voucherTypeId: Long,
        val cashTypeId: Long,
        val voucherId: Long,
    )

    private var voucherFixture: VoucherFixture? = null

    private fun setupBillPaidByVoucher() {
        val admin = TestData.createAdmin()
        TestData.createShopInfo()
        val vats = TestData.createDefaultVatRates()
        val cash = TestData.createPaymentType("Cash", isCash = true)
        val voucherType = TestData.createPaymentType("Voucher", isCash = false, isVoucher = true)
        val tws = TestData.createTableWithSession("SVR1")
        val category = TestData.createCategory("Voucher Cat")
        val product = TestData.createProduct(category.id.value, "Voucher Product", "VCH001")

        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TERM-TEST-SVR1")

        transaction {
            val now = currentTimeStamp()
            val session = TableSession.findById(tws.session.id)!!
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "SVR-1"
                    this.billAmount = BigDecimal("30.00")
                    this.paidAmount = BigDecimal("30.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                }
            val payment =
                Payment.new {
                    this.bill = bill
                    this.paymentType = PaymentType.findById(voucherType.id.value)!!
                    this.amount = BigDecimal("30.00")
                    this.currency = "EUR"
                    this.paymentStatus = PaymentStatus.COMPLETED
                    this.paymentTime = now
                }
            val voucherItem =
                OrderItem.new {
                    this.session = session
                    this.product = product
                    this.unitPrice = BigDecimal("30.00")
                    this.finalPrice = BigDecimal("30.00")
                    this.itemStatus = ItemStatus.PAID
                    this.vat = vats[0]
                    this.takeAway = false
                }
            val voucher =
                Voucher.new {
                    this.orderItem = voucherItem
                    this.voucherNumber = "G2026-0001"
                    this.originalValue = BigDecimal("30.00")
                    this.remainingValue = BigDecimal.ZERO
                    this.fullyRedeemedAt = now
                    this.createdAt = now
                }
            VoucherRedeem.new {
                this.voucher = voucher
                this.payment = payment
                this.redeemAmount = BigDecimal("30.00")
                this.redeemedAt = now
                this.voucherType = VoucherSourceType.INTERNAL
                this.voucherNumber = "G2026-0001"
            }
            voucherFixture = VoucherFixture(bill.id.value, voucherType.id.value, cash.id.value, voucher.id.value)
        }
    }

    @Test
    fun `real deleteBill on a voucher bill stamps reversedByBill and restores once`() =
        testApp(
            setupData = { setupBillPaidByVoucher() },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            val f = voucherFixture!!

            // Storno the voucher-paid bill through the real deleteBill path: it must restore the voucher
            // balance once, clear fullyRedeemedAt, and stamp the redemption's reversedByBill with the
            // created reverse bill.
            val delete = client.delete("/api/v1/bills/${f.billId}?reason=test") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, delete.status, "body: ${delete.bodyAsText()}")

            transaction {
                val voucher = Voucher.findById(f.voucherId)!!
                assertEquals(
                    0,
                    voucher.remainingValue.compareTo(BigDecimal("30.00")),
                    "voucher restored exactly once (expected 30.00): actual=${voucher.remainingValue}",
                )
                assertNull(voucher.fullyRedeemedAt)

                val reverseBill = Bill.find { BillTable.deletedBillId eq f.billId }.single()
                val originalPayment = Bill.findById(f.billId)!!.payments.single()
                assertEquals(
                    reverseBill.id.value,
                    originalPayment.voucherRedeem
                        ?.reversedByBill
                        ?.id
                        ?.value,
                )
            }
        }
}
