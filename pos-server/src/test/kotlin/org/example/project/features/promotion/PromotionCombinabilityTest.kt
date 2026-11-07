package org.example.project.features.promotion

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.promotion.PromotionDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.example.project.features.promotion.domain.models.BillPromotion
import org.example.project.features.promotion.domain.models.Promotion
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
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
 * `Promotion.combinable` says whether a promotion may sit on a bill *alongside other promotions*
 * ("Nicht mit anderen Aktionen kombinierbar"). It is a different axis from `isMultiUse`, which says
 * whether the SAME promotion may be applied several times within one bill.
 *
 * Three cases are pinned here:
 *  1. the pre-existing stacking behaviour (ITEM_SPECIFIC first, BILL_WIDE on the remainder), which
 *     the new flag must not disturb for combinable promotions;
 *  2. a non-combinable promotion offered together with another one — rejected, and rejected before
 *     anything is written;
 *  3. the same non-combinable promotion on its own — applies normally.
 */
@DisplayName("Promotion combinability guard")
class PromotionCombinabilityTest {
    private val deviceId = "promo-comb-device"

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    /** Everything one of the three scenarios needs from the shared fixture. */
    private data class Fixture(
        val adminId: String,
        val sessionId: Long,
        val itemAId: Long,
        val itemBId: Long,
        val cashTypeId: Long,
        val itemPromotionId: Long,
        val billWidePromotionId: Long,
    )

    /**
     * A 23.50 bill at 19 %: a 19.00 item the ITEM_SPECIFIC promotion targets plus a 4.50 item it
     * does not. [nonCombinableItemPromotion] flips only the ITEM_SPECIFIC promotion's flag.
     */
    private fun buildFixture(
        prefix: String,
        nonCombinableItemPromotion: Boolean,
    ): Fixture {
        val admin = TestData.createAdmin(employeeNumber = "${prefix}_ADM", username = "${prefix.lowercase()}_adm")
        val vats = TestData.createDefaultVatRates()
        val vat19 = vats.first { it.vatRate.compareTo(BigDecimal("0.1900")) == 0 }
        val cash = TestData.createPaymentType("Cash_$prefix", isCash = true)
        val tws = TestData.createTableWithSession("${prefix}TBL")
        val category = TestData.createCategory("$prefix Cat")
        val productA =
            TestData.createProduct(
                categoryId = category.id.value,
                productName = "$prefix Prod A",
                productNumber = "${prefix}_P1",
                price = BigDecimal("19.0000"),
            )
        val productB =
            TestData.createProduct(
                categoryId = category.id.value,
                productName = "$prefix Prod B",
                productNumber = "${prefix}_P2",
                price = BigDecimal("4.5000"),
            )

        transaction {
            Terminal.new {
                this.deviceId = this@PromotionCombinabilityTest.deviceId + "-" + prefix
                this.clientId = "TERM-$prefix"
                this.registeredAt = currentTimeStamp()
                this.lastSeenAt = currentTimeStamp()
            }
        }

        return transaction {
            val now = currentTimeStamp()
            val itemPromo =
                Promotion.new {
                    this.name = "5 EUR auf $prefix Prod A"
                    this.description = null
                    this.discountType = DiscountType.FIXED_AMOUNT
                    this.discountValue = BigDecimal("5.00")
                    this.scope = PromotionScope.ITEM_SPECIFIC
                    this.targetProduct = productA
                    this.isMultiUse = false
                    this.combinable = !nonCombinableItemPromotion
                    this.validFrom = now
                    this.validUntil = null
                    this.isActive = true
                    this.createdBy = admin
                    this.createdAt = now
                }
            val billWidePromo =
                Promotion.new {
                    this.name = "10 % auf alles ($prefix)"
                    this.description = null
                    this.discountType = DiscountType.PERCENTAGE
                    this.discountValue = BigDecimal("10.00")
                    this.scope = PromotionScope.BILL_WIDE
                    this.targetProduct = null
                    this.isMultiUse = false
                    this.combinable = true
                    this.validFrom = now
                    this.validUntil = null
                    this.isActive = true
                    this.createdBy = admin
                    this.createdAt = now
                }

            val session = TableSession.findById(tws.session.id)!!
            session.employeeId = admin
            val itemA =
                OrderItem.new {
                    this.session = session
                    this.product = productA
                    this.unitPrice = BigDecimal("19.00")
                    this.finalPrice = BigDecimal("19.00")
                    this.itemStatus = ItemStatus.OPEN
                    this.vat = vat19
                    this.discount = BigDecimal.ONE
                    this.customPrice = false
                    this.takeAway = false
                    this.transferred = false
                    this.sideNotes = null
                }
            val itemB =
                OrderItem.new {
                    this.session = session
                    this.product = productB
                    this.unitPrice = BigDecimal("4.50")
                    this.finalPrice = BigDecimal("4.50")
                    this.itemStatus = ItemStatus.OPEN
                    this.vat = vat19
                    this.discount = BigDecimal.ONE
                    this.customPrice = false
                    this.takeAway = false
                    this.transferred = false
                    this.sideNotes = null
                }

            Fixture(
                adminId = admin.id.value.toString(),
                sessionId = session.id.value,
                itemAId = itemA.id.value,
                itemBId = itemB.id.value,
                cashTypeId = cash.id.value,
                itemPromotionId = itemPromo.id.value,
                billWidePromotionId = billWidePromo.id.value,
            )
        }
    }

    @Test
    @DisplayName("two combinable promotions still stack: ITEM_SPECIFIC first, BILL_WIDE on the remainder")
    fun combinablePromotionsStillStack() {
        var billId = 0L
        testApp(
            setupData = {
                val f = buildFixture("COMBOK", nonCombinableItemPromotion = false)
                val billService = GlobalContext.get().get<BillService>()
                val response =
                    runBlocking {
                        billService.createBill(
                            employeeId = f.adminId,
                            deviceId = "$deviceId-COMBOK",
                            request =
                                BillDto.CreateBillRequestDto(
                                    tableSessionId = f.sessionId,
                                    orderItems = listOf(f.itemAId, f.itemBId),
                                    billAmount = "23.50",
                                    tips = "0.00",
                                    // 23.50 - 5.00 - 1.85 = 16.65
                                    payments =
                                        listOf(
                                            PaymentDto.PaymentRequest(amount = "16.65", paymentTypeId = f.cashTypeId),
                                        ),
                                    promotions =
                                        listOf(
                                            PromotionDto.PromotionApplyRequestDto(
                                                promotionId = f.itemPromotionId,
                                                orderItemId = f.itemAId,
                                            ),
                                            PromotionDto.PromotionApplyRequestDto(
                                                promotionId = f.billWidePromotionId,
                                                orderItemId = null,
                                            ),
                                        ),
                                ),
                        )
                    }
                billId = response.billId
            },
        ) { _ ->
            transaction {
                val bill = Bill.findById(billId)!!
                assertEquals(
                    0,
                    bill.promotionAmount.compareTo(BigDecimal("6.85")),
                    "5.00 first, then 10 % of the remaining 18.50 = 1.85; was ${bill.promotionAmount}",
                )
                val details = bill.vatDetails.toList()
                assertEquals(1, details.size, "one 19 % VAT group expected, was ${details.size}")
                val detail = details.single()
                assertEquals(
                    0,
                    detail.totalAmount.compareTo(BigDecimal("16.65")),
                    "taxable base after both promotions, was ${detail.totalAmount}",
                )
                assertEquals(0, detail.netAmount.compareTo(BigDecimal("13.99")), "net was ${detail.netAmount}")
                assertEquals(0, detail.vatAmount.compareTo(BigDecimal("2.66")), "vat was ${detail.vatAmount}")
                assertEquals(2, BillPromotion.find { BillPromotionTable.billId eq billId }.count().toInt())
            }
        }
    }

    @Test
    @DisplayName("a non-combinable promotion offered with another one is rejected and writes nothing")
    fun nonCombinablePromotionWithAnotherIsRejected() {
        var thrown: Throwable? = null
        var sessionId = 0L
        testApp(
            setupData = {
                val f = buildFixture("COMBNO", nonCombinableItemPromotion = true)
                sessionId = f.sessionId
                val billService = GlobalContext.get().get<BillService>()
                thrown =
                    runCatching {
                        runBlocking {
                            billService.createBill(
                                employeeId = f.adminId,
                                deviceId = "$deviceId-COMBNO",
                                request =
                                    BillDto.CreateBillRequestDto(
                                        tableSessionId = f.sessionId,
                                        orderItems = listOf(f.itemAId, f.itemBId),
                                        billAmount = "23.50",
                                        tips = "0.00",
                                        payments =
                                            listOf(
                                                PaymentDto.PaymentRequest(amount = "16.65", paymentTypeId = f.cashTypeId),
                                            ),
                                        promotions =
                                            listOf(
                                                PromotionDto.PromotionApplyRequestDto(
                                                    promotionId = f.itemPromotionId,
                                                    orderItemId = f.itemAId,
                                                ),
                                                PromotionDto.PromotionApplyRequestDto(
                                                    promotionId = f.billWidePromotionId,
                                                    orderItemId = null,
                                                ),
                                            ),
                                    ),
                            )
                        }
                    }.exceptionOrNull()
            },
        ) { _ ->
            val error = assertNotNull(thrown, "createBill must reject a non-combinable promotion offered with another")
            val posException =
                generateSequence(error) { it.cause }
                    .filterIsInstance<PosException>()
                    .firstOrNull()
            assertNotNull(posException, "expected a PosException, got ${error::class.simpleName}: ${error.message}")
            assertEquals(PosErrorCode.PROMOTION_NOT_COMBINABLE, posException.code)
            assertEquals(HttpStatusCode.Conflict, posException.httpStatus)
            assertTrue(
                posException.message?.contains("5 EUR auf COMBNO Prod A") == true,
                "the message must name the offending promotion, was ${posException.message}",
            )

            transaction {
                assertEquals(
                    0,
                    Bill
                        .find {
                            BillTable.tableSession eq sessionId
                        }.count()
                        .toInt(),
                    "a rejected bill must not be persisted",
                )
                assertEquals(
                    0,
                    BillPromotion.all().count().toInt(),
                    "a rejected bill must write no BillPromotion rows",
                )
            }
        }
    }

    @Test
    @DisplayName("a non-combinable promotion applied alone still works")
    fun nonCombinablePromotionAloneApplies() {
        var billId = 0L
        testApp(
            setupData = {
                val f = buildFixture("CMBSOLO", nonCombinableItemPromotion = true)
                val billService = GlobalContext.get().get<BillService>()
                val response =
                    runBlocking {
                        billService.createBill(
                            employeeId = f.adminId,
                            deviceId = "$deviceId-CMBSOLO",
                            request =
                                BillDto.CreateBillRequestDto(
                                    tableSessionId = f.sessionId,
                                    orderItems = listOf(f.itemAId, f.itemBId),
                                    billAmount = "23.50",
                                    tips = "0.00",
                                    payments =
                                        listOf(
                                            PaymentDto.PaymentRequest(amount = "18.50", paymentTypeId = f.cashTypeId),
                                        ),
                                    promotions =
                                        listOf(
                                            PromotionDto.PromotionApplyRequestDto(
                                                promotionId = f.itemPromotionId,
                                                orderItemId = f.itemAId,
                                            ),
                                        ),
                                ),
                        )
                    }
                billId = response.billId
            },
        ) { _ ->
            transaction {
                val bill = Bill.findById(billId)!!
                assertEquals(
                    0,
                    bill.promotionAmount.compareTo(BigDecimal("5.00")),
                    "a lone non-combinable promotion applies unchanged, was ${bill.promotionAmount}",
                )
            }
        }
    }
}
