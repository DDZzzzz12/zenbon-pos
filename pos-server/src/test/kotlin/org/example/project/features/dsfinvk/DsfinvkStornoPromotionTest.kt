package org.example.project.features.dsfinvk

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.promotion.domain.models.BillPromotion
import org.example.project.features.promotion.domain.models.Promotion
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Pins the DSFinV-K promotion sign-flip fix for a bill storno (round-3 KABS-2).
 *
 * A sale with a BILL_WIDE promotion (100.00 item, −20.00 Rabatt, 80.00 cash) that is fully
 * storno'd must have its `businesscases.csv` Rabatt GV_TYP aggregate net to zero across the
 * original + reverse Belege of the closing. Reverse bills carry NO `BillPromotion` rows, so
 * the promotions query has to resolve them on the ORIGINAL bill (via `sourceBillId`, exactly
 * like the voucher-redemption block) and `promoSign` then negates the re-aggregation.
 * Previously the query used the reverse bill's OWN id, found nothing, and never reversed the
 * original's −20.00 Rabatt aggregate, leaving the Rabatt total at −20.00 after a full storno.
 */
@DisplayName("DSFinV-K promotion — storno nets the Rabatt aggregate (KABS-2)")
class DsfinvkStornoPromotionTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `full storno of a promoted sale nets the Rabatt businesscases aggregate to zero`() =
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val vats = TestData.createDefaultVatRates()
            val cash = TestData.createPaymentType("Cash", isCash = true, isVoucher = false)
            val tws = TestData.createTableWithSession("SPR1")
            val category = TestData.createCategory("Storno Promo Cat")
            val product = TestData.createProduct(category.id.value, "Storno Promo Product", "SPR001")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-SPR-TEST"
                registeredAt = now
                lastSeenAt = now
            }

            val zBon =
                ZBon.new {
                    this.shopInfo = shop
                    this.employee = admin
                    this.startTime = now
                    this.endTime = now
                    this.cancelledAmount = BigDecimal.ZERO
                    this.totalDiscounts = BigDecimal.ZERO
                    this.totalPosRevenue = BigDecimal.ZERO
                    this.adminTip = BigDecimal.ZERO
                    this.openingBalance = BigDecimal.ZERO
                    this.expenses = BigDecimal.ZERO
                    this.cashRevenue = BigDecimal.ZERO
                    this.closingBalance = BigDecimal.ZERO
                    this.createdAt = now
                    this.trainingRevenue = BigDecimal.ZERO
                    this.trainingBillCount = 0
                    this.refundAmount = BigDecimal.ZERO
                    this.stornoBillCount = 0
                    this.zbonNummer = 1L
                }

            val session = TableSession.findById(tws.session.id)!!
            val vat19 = vats[0]

            // Sale: 100.00 item, BILL_WIDE promotion deducting 20.00, 80.00 cash.
            val originalBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "SPR-1001"
                    this.billAmount = BigDecimal("100.00")
                    this.promotionAmount = BigDecimal("20.00")
                    this.paidAmount = BigDecimal("80.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            // Reverse bill: all amounts negated (mirrors BillServiceImpl.deleteBill). It carries
            // NO BillPromotion row — that is exactly why the query must resolve via sourceBillId.
            val reverseBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "SPR-2002"
                    this.billAmount = BigDecimal("-100.00")
                    this.promotionAmount = BigDecimal("-20.00")
                    this.paidAmount = BigDecimal("-80.00")
                    this.tips = BigDecimal.ZERO
                    this.deletedBill = originalBill
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }

            OrderItem.new {
                this.session = session
                this.product = product
                this.bill = originalBill
                this.unitPrice = BigDecimal("100.00")
                this.finalPrice = BigDecimal("100.00")
                this.itemStatus = ItemStatus.PAID
                this.vat = vat19
                this.takeAway = false
            }

            Payment.new {
                this.bill = originalBill
                this.paymentType = cash
                this.amount = BigDecimal("80.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.REFUNDED
                this.paymentTime = now
                this.refundTime = now
                this.refundedBillId = reverseBill
            }

            val promotion =
                Promotion.new {
                    this.name = "20 EUR off bill"
                    this.description = null
                    this.discountType = DiscountType.FIXED_AMOUNT
                    this.discountValue = BigDecimal("20.00")
                    this.scope = PromotionScope.BILL_WIDE
                    this.targetProduct = null
                    this.isMultiUse = false
                    this.validFrom = now
                    this.validUntil = null
                    this.isActive = true
                    this.createdBy = admin
                    this.createdAt = now
                }
            // BILL_WIDE: orderItem left null so the aggregate apportions across the item VAT rates.
            BillPromotion.new {
                this.bill = originalBill
                this.promotion = promotion
                this.orderItem = null
                this.deductedAmount = BigDecimal("20.00")
                this.appliedBy = admin
                this.appliedAt = now
            }

            // businesscases.csv Rabatt GV_TYP aggregate must net to zero after full storno.
            val businessCases = service.generateBusinessCases(zBons = listOf(zBon), cashRegisterId = "SN-1")
            val rabattRows = businessCases.filter { it.gvTyp == DsfinvkExportDto.BusinessCaseTypes.RABATT }
            val netRabatt = rabattRows.sumOf { BigDecimal(it.zUmsBrutto) }
            assertEquals(
                0,
                netRabatt.compareTo(BigDecimal.ZERO),
                "storno must net the Rabatt aggregate to zero; got $netRabatt from $rabattRows",
            )
        }
}
