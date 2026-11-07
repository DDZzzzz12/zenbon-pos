package org.example.project.features.promotion

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.promotion.domain.models.Promotion
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
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
import kotlin.test.assertTrue

/**
 * Creation-time validation rejects a FIXED_AMOUNT discount above its target product's price, but it
 * cannot be the only guard: the catalog price may be lowered afterwards, and an `allowCustomPrice`
 * item can be sold below catalog anyway. The runtime clamp in `BillServiceImpl`
 * (`minOf(discountValue, targetItem.finalPrice)`) is what actually prevents a negative line item —
 * which would put a negative gross into a VAT bucket and from there into the Z-Bon and the
 * DSFinV-K export.
 *
 * This test lowers the price after the promotion is created, which is exactly the case the
 * creation-time check cannot see.
 */
@DisplayName("Promotion deduction clamp survives a later price drop")
class PromotionClampRegressionTest {
    private val deviceId = "promo-clamp-device"

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    @Test
    fun `a FIXED_AMOUNT promotion is clamped to the item price after the product price is lowered`() {
        var billId = 0L
        testApp(
            setupData = {
                val admin = TestData.createAdmin(employeeNumber = "CLMP_ADM", username = "clmp_adm")
                val vats = TestData.createDefaultVatRates()
                val vat19 = vats.first { it.vatRate.compareTo(BigDecimal("0.1900")) == 0 }
                val cash = TestData.createPaymentType("Cash_CLMP", isCash = true)
                val tws = TestData.createTableWithSession("CLMPTBL")
                val category = TestData.createCategory("Clamp Cat")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "Clamp Prod",
                        productNumber = "CLMP_P1",
                        price = BigDecimal("19.0000"),
                    )

                transaction {
                    Terminal.new {
                        this.deviceId = this@PromotionClampRegressionTest.deviceId
                        this.clientId = "TERM-PROMO-CLAMP"
                        this.registeredAt = currentTimeStamp()
                        this.lastSeenAt = currentTimeStamp()
                    }
                }

                val ids =
                    transaction {
                        val now = currentTimeStamp()
                        // Valid at creation: 19.00 discount on a 19.00 product (the boundary case).
                        val promoId =
                            Promotion
                                .new {
                                    this.name = "19 EUR auf Clamp Prod"
                                    this.description = null
                                    this.discountType = DiscountType.FIXED_AMOUNT
                                    this.discountValue = BigDecimal("19.00")
                                    this.scope = PromotionScope.ITEM_SPECIFIC
                                    this.targetProduct = product
                                    this.isMultiUse = false
                                    this.validFrom = now
                                    this.validUntil = null
                                    this.isActive = true
                                    this.createdBy = admin
                                    this.createdAt = now
                                }.id.value

                        // The manager later lowers the price — the creation-time check cannot see this.
                        product.price = BigDecimal("10.0000")

                        val session = TableSession.findById(tws.session.id)!!
                        session.employeeId = admin
                        val itemId =
                            OrderItem
                                .new {
                                    this.session = session
                                    this.product = product
                                    this.unitPrice = BigDecimal("10.00")
                                    this.finalPrice = BigDecimal("10.00")
                                    this.itemStatus = ItemStatus.OPEN
                                    this.vat = vat19
                                    this.discount = BigDecimal.ONE
                                    this.customPrice = false
                                    this.takeAway = false
                                    this.transferred = false
                                    this.sideNotes = null
                                }.id.value
                        listOf(itemId, promoId)
                    }

                val billService = GlobalContext.get().get<BillService>()
                val response =
                    runBlocking {
                        billService.createBill(
                            employeeId = admin.id.value.toString(),
                            deviceId = deviceId,
                            request =
                                BillDto.CreateBillRequestDto(
                                    tableSessionId = tws.session.id.value,
                                    orderItems = listOf(ids[0]),
                                    billAmount = "10.00",
                                    tips = "0.00",
                                    // The item is fully discounted, so nothing is tendered.
                                    payments = listOf(PaymentDto.PaymentRequest(amount = "0.00", paymentTypeId = cash.id.value)),
                                    promotions =
                                        listOf(
                                            PromotionDto.PromotionApplyRequestDto(promotionId = ids[1], orderItemId = ids[0]),
                                        ),
                                ),
                        )
                    }
                billId = response.billId
            },
        ) { _ ->
            transaction {
                val bill = Bill.findById(billId)!!

                // Clamped to the item's 10.00, NOT the promotion's 19.00.
                assertEquals(
                    0,
                    bill.promotionAmount.compareTo(BigDecimal("10.00")),
                    "deduction must clamp to the item price after the drop, was ${bill.promotionAmount}",
                )

                // The whole point of the clamp: no negative gross reaches a VAT bucket.
                bill.vatDetails.forEach { detail ->
                    assertTrue(
                        detail.totalAmount.signum() >= 0,
                        "a clamped promotion must never drive a VAT bucket negative, was ${detail.totalAmount}",
                    )
                }
                assertEquals(
                    0,
                    bill.vatDetails.sumOf { it.totalAmount }.compareTo(BigDecimal.ZERO),
                    "a fully discounted bill nets to zero",
                )
            }
        }
    }
}
