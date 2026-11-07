package org.example.project.features.bill

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.core.domain.enums.tse.BonType
import org.example.project.core.domain.enums.tse.ProcessType
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.example.project.features.promotion.domain.models.BillPromotion
import org.example.project.features.promotion.domain.models.Promotion
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.builder.ProcessDataBuilder
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TSER-3 regression guard: an `ITEM_SPECIFIC` PERCENTAGE promotion deduction must be a
 * scale-2 (penny) amount.
 *
 * A promotion deduction is a **terminal** monetary amount — it lands verbatim on the printed
 * Beleg, in `BillPromotion.deductedAmount`, and via `groupTotal -= deduction` in a signed
 * `BillVatDetail` bucket (`bill_vat_detail.total_amount` is `decimal(19,4)`, so a sub-cent
 * really does persist). `ProcessDataBuilder.fmtAmount` truncates (`RoundingMode.DOWN`, Anhang I:
 * „alle anderen Dezimalstellen werden ggf. abgeschnitten"), so a scale-4 deduction leaves a
 * sub-cent residue that the truncation silently drops — `Σ Umsatz` then lands a cent **below**
 * `Σ Zahlung` in the signed Kassenbeleg-V1, a permanent Anhang I p.116 violation. Neither the
 * `Σ vatDetails == paidAmount` guard (BillServiceImpl step 8) nor the payment-total guard
 * (step 5) catches it: both use scale-blind `compareTo`.
 *
 * Fixture (deliberately payment-stable, so the SAME bill exercises pre- and post-fix):
 * ```
 *   item A  10.12 @19 %  −5 % → 0.5060      item B  30.08 @7 %  −5 % → 1.5040
 *   Σ deduction 2.0100 at scale 4  ==  0.51 + 1.50 = 2.01 at scale 2
 *   → billAmount 40.20, promotion 2.01, paid 38.19 both before AND after the fix
 * ```
 * The two scale-4 residues (+0.0060 / +0.0040) sum to a whole cent, so the aggregate guards pass
 * either way; but they round in OPPOSITE directions under HALF_UP, so the payment total is
 * unchanged while the per-group buckets are not:
 * ```
 *   pre-fix  19 %: 10.12 − 0.5060 = 9.6140   7 %: 30.08 − 1.5040 = 28.5760   (Σ 38.1900 ✓ guard)
 *            fmtAmount DOWN       → 9.61  +  28.57  =  38.18  ≠  38.19 paid   ✗ UNBALANCED
 *   post-fix 19 %: 10.12 − 0.51   = 9.61     7 %: 30.08 − 1.50   = 28.58      (Σ 38.19   ✓ guard)
 *            fmtAmount DOWN       → 9.61  +  28.58  =  38.19  ==  38.19 paid  ✓ BALANCED
 * ```
 * This is the same `setScale(4)` call site flagged as carried finding C3 (two ITEM_SPECIFIC
 * PERCENTAGE deductions in different VAT groups whose residues cancel, leaving each group's
 * gross sub-cent for `ZBonServiceImpl` to round independently) — fixing it here closes that at
 * the source, so no VAT group can carry a sub-cent gross in the first place.
 *
 * `BILL_WIDE` percentage deductions round the same way (scale 2, HALF_UP); see
 * [BillWidePromotionRoundingTest].
 */
@DisplayName("ITEM_SPECIFIC percentage promotion deduction scale (TSER-3)")
class BillItemPromotionScaleTest {
    private val deviceId = "item-promo-scale-device"

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    private fun createTerminal() =
        transaction {
            val now = currentTimeStamp()
            Terminal.new {
                this.deviceId = this@BillItemPromotionScaleTest.deviceId
                this.clientId = "TERM-ITEM-PROMO-SCALE"
                this.registeredAt = now
                this.lastSeenAt = now
            }
        }

    @Test
    fun `two ITEM_SPECIFIC percentage promos across rates keep signed buckets == payment (TSER-3)`() =
        testApp(
            setupData = {
                val admin = TestData.createAdmin(employeeNumber = "IPS_ADM", username = "ips_adm")
                val vats = TestData.createDefaultVatRates()
                val vat19 = vats[0]
                val vat7 = vats[1]
                val cash = TestData.createPaymentType("Cash_IPS", isCash = true)
                val tws = TestData.createTableWithSession("IPSPROMO")
                val category = TestData.createCategory("IPS Cat")
                val productA = TestData.createProduct(category.id.value, "IPS A", "IPS_A", BigDecimal("10.1200"))
                val productB = TestData.createProduct(category.id.value, "IPS B", "IPS_B", BigDecimal("30.0800"))
                createTerminal()

                val ids =
                    transaction {
                        val session = TableSession.findById(tws.session.id)!!
                        session.employeeId = admin
                        val now = currentTimeStamp()
                        val itemA =
                            OrderItem
                                .new {
                                    this.session = session
                                    this.product = productA
                                    this.unitPrice = BigDecimal("10.12")
                                    this.finalPrice = BigDecimal("10.12")
                                    this.itemStatus = ItemStatus.OPEN
                                    this.vat = vat19
                                    this.discount = BigDecimal.ONE
                                    this.customPrice = false
                                    this.takeAway = false
                                    this.transferred = false
                                    this.sideNotes = null
                                }.id.value
                        val itemB =
                            OrderItem
                                .new {
                                    this.session = session
                                    this.product = productB
                                    this.unitPrice = BigDecimal("30.08")
                                    this.finalPrice = BigDecimal("30.08")
                                    this.itemStatus = ItemStatus.OPEN
                                    this.vat = vat7
                                    this.discount = BigDecimal.ONE
                                    this.customPrice = false
                                    this.takeAway = false
                                    this.transferred = false
                                    this.sideNotes = null
                                }.id.value

                        fun promoFor(
                            target: org.example.project.features.product.domain.models.Product,
                            name: String,
                        ) = Promotion
                            .new {
                                this.name = name
                                this.description = null
                                this.discountType = DiscountType.PERCENTAGE
                                this.discountValue = BigDecimal("5")
                                this.scope = PromotionScope.ITEM_SPECIFIC
                                this.targetProduct = target
                                this.isMultiUse = false
                                this.validFrom = now
                                this.validUntil = null
                                this.isActive = true
                                this.createdBy = admin
                                this.createdAt = now
                            }.id.value
                        listOf(itemA, itemB, promoFor(productA, "5% off A"), promoFor(productB, "5% off B"))
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
                                    orderItems = listOf(ids[0], ids[1]),
                                    billAmount = "40.20",
                                    tips = "0.00",
                                    payments =
                                        listOf(
                                            PaymentDto.PaymentRequest(amount = "38.19", paymentTypeId = cash.id.value),
                                        ),
                                    promotions =
                                        listOf(
                                            PromotionDto.PromotionApplyRequestDto(promotionId = ids[2], orderItemId = ids[0]),
                                            PromotionDto.PromotionApplyRequestDto(promotionId = ids[3], orderItemId = ids[1]),
                                        ),
                                ),
                        )
                    }

                transaction {
                    val bill = Bill.findById(response.billId)!!

                    // Anhang I mandates truncation to EXACTLY two decimals, so assert the exact
                    // signed string — a substring check would not catch the dropped sub-cent.
                    val processData =
                        ProcessDataBuilder.build(
                            processType = ProcessType.KASSENBELEG_V1,
                            bonType = BonType.BELEG,
                            vatDetail = bill.vatDetails.toList(),
                            payments = bill.payments.toList(),
                        )
                    assertEquals(
                        "Beleg^9.61_28.58_0.00_0.00_0.00^38.19:Bar",
                        processData,
                        "signed Kassenbeleg-V1 must balance (Σ Umsatz == Σ Zahlung) after fmtAmount's " +
                            "DOWN truncation; a scale-4 ITEM_SPECIFIC deduction drops a sub-cent and " +
                            "yields 9.61_28.57 = 38.18 against a 38.19 payment",
                    )

                    // Every signed bucket must be free of sub-cent residue by construction, so
                    // fmtAmount's DOWN and the receipt's HALF_UP agree on every surface.
                    bill.vatDetails.forEach {
                        assertTrue(
                            it.totalAmount.stripTrailingZeros().scale() <= 2,
                            "BillVatDetail must carry no sub-cent; got ${it.totalAmount.toPlainString()}",
                        )
                    }

                    // The deduction is terminal — the audit row the Beleg and the export both read
                    // must itself be a penny amount.
                    BillPromotion.find { BillPromotionTable.billId eq bill.id }.forEach {
                        assertTrue(
                            it.deductedAmount.stripTrailingZeros().scale() <= 2,
                            "BillPromotion.deductedAmount must be a penny amount; got " +
                                it.deductedAmount.toPlainString(),
                        )
                    }

                    assertEquals(
                        0,
                        bill.promotionAmount.compareTo(BigDecimal("2.01")),
                        "sanity: Σ deduction is 2.01 at BOTH scales, so the payment total is " +
                            "unchanged by the fix; got ${bill.promotionAmount.toPlainString()}",
                    )
                }
            },
        ) { _ -> }
}
