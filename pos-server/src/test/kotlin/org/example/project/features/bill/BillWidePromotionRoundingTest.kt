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
 * A `BILL_WIDE` PERCENTAGE promotion deduction is a terminal monetary amount: rounded HALF_UP to
 * the cent when applied, like the `ITEM_SPECIFIC` one (TSER-3) and like the till.
 *
 * It used to stay at scale 4, so 10 % of 29.68 gave 2.9680 and a bill total of 26.7120 that no
 * cent-rounded tender could ever equal: `PAYMENT_TOTAL_MISMATCH`, an unpayable bill. Found while
 * seeding a QA server (2026-10-13).
 */
@DisplayName("BILL_WIDE percentage promotion rounds to the cent")
class BillWidePromotionRoundingTest {
    private val deviceId = "bill-wide-rounding-device"

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    private fun createTerminal() =
        transaction {
            val now = currentTimeStamp()
            Terminal.new {
                this.deviceId = this@BillWidePromotionRoundingTest.deviceId
                this.clientId = "TERM-BILLWIDE-ROUND"
                this.registeredAt = now
                this.lastSeenAt = now
            }
        }

    private fun item(
        session: TableSession,
        product: org.example.project.features.product.domain.models.Product,
        price: String,
        vat: org.example.project.features.payment.domain.models.Vat,
    ) = OrderItem
        .new {
            this.session = session
            this.product = product
            this.unitPrice = BigDecimal(price)
            this.finalPrice = BigDecimal(price)
            this.itemStatus = ItemStatus.OPEN
            this.vat = vat
            this.discount = BigDecimal.ONE
            this.customPrice = false
            this.takeAway = false
            this.transferred = false
            this.sideNotes = null
        }.id.value

    /** Bills one 19 % item and one 7 % item with a bill-wide 10 % promotion; returns the created bill. */
    private fun billWithTenPercent(
        price19: String,
        price7: String,
        billAmount: String,
        payment: String,
        check: (Bill) -> Unit,
    ) = testApp(
        setupData = {
            val admin = TestData.createAdmin(employeeNumber = "BWR_ADM", username = "bwr_adm")
            val vats = TestData.createDefaultVatRates()
            val cash = TestData.createPaymentType("Cash_BWR", isCash = true)
            val tws = TestData.createTableWithSession("BWRPROMO")
            val category = TestData.createCategory("BWR Cat")
            val productA = TestData.createProduct(category.id.value, "BWR A", "BWR_A", BigDecimal(price19))
            val productB = TestData.createProduct(category.id.value, "BWR B", "BWR_B", BigDecimal(price7))
            createTerminal()

            val ids =
                transaction {
                    val session = TableSession.findById(tws.session.id)!!
                    session.employeeId = admin
                    val now = currentTimeStamp()
                    val promotionId =
                        Promotion
                            .new {
                                this.name = "Happy Hour 10 %"
                                this.description = null
                                this.discountType = DiscountType.PERCENTAGE
                                this.discountValue = BigDecimal("10")
                                this.scope = PromotionScope.BILL_WIDE
                                this.targetProduct = null
                                this.isMultiUse = false
                                this.validFrom = now
                                this.validUntil = null
                                this.isActive = true
                                this.createdBy = admin
                                this.createdAt = now
                            }.id.value
                    listOf(item(session, productA, price19, vats[0]), item(session, productB, price7, vats[1]), promotionId)
                }

            val response =
                runBlocking {
                    GlobalContext.get().get<BillService>().createBill(
                        employeeId = admin.id.value.toString(),
                        deviceId = deviceId,
                        request =
                            BillDto.CreateBillRequestDto(
                                tableSessionId = tws.session.id.value,
                                orderItems = listOf(ids[0], ids[1]),
                                billAmount = billAmount,
                                tips = "0.00",
                                payments = listOf(PaymentDto.PaymentRequest(amount = payment, paymentTypeId = cash.id.value)),
                                promotions = listOf(PromotionDto.PromotionApplyRequestDto(promotionId = ids[2])),
                            ),
                    )
                }
            transaction { check(Bill.findById(response.billId)!!) }
        },
    ) { _ -> }

    @Test
    fun `10 percent of 29_68 is 2_97 and the bill is payable at 26_71`() =
        billWithTenPercent("10.12", "19.56", "29.68", "26.71") { bill ->
            assertEquals(0, bill.promotionAmount.compareTo(BigDecimal("2.97")), "promotionAmount ${bill.promotionAmount.toPlainString()}")
            BillPromotion.find { BillPromotionTable.billId eq bill.id }.forEach {
                assertEquals(0, it.deductedAmount.compareTo(BigDecimal("2.97")), "deductedAmount ${it.deductedAmount.toPlainString()}")
            }
            bill.vatDetails.forEach {
                assertTrue(it.totalAmount.stripTrailingZeros().scale() <= 2, "sub-cent bucket ${it.totalAmount.toPlainString()}")
            }
            // Σ signed Umsatz == Σ Zahlung: the cent left over by the apportionment goes to the 7 % group.
            assertEquals(
                "Beleg^9.11_17.60_0.00_0.00_0.00^26.71:Bar",
                ProcessDataBuilder.build(
                    processType = ProcessType.KASSENBELEG_V1,
                    bonType = BonType.BELEG,
                    vatDetail = bill.vatDetails.toList(),
                    payments = bill.payments.toList(),
                ),
            )
        }

    @Test
    fun `a cent-exact bill-wide percentage is unchanged`() =
        billWithTenPercent("20.00", "10.00", "30.00", "27.00") { bill ->
            assertEquals(0, bill.promotionAmount.compareTo(BigDecimal("3.00")), "promotionAmount ${bill.promotionAmount.toPlainString()}")
        }
}
