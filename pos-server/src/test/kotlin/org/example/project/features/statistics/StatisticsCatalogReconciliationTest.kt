package org.example.project.features.statistics

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.product.domain.models.Product
import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.example.project.features.promotion.database.tables.PromotionTable
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.statistics.data.repository.StatisticsRepositoryImpl
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * admin17 NOTE-11: the Katalog's category "Umsatz" summed to the list prices (279,90 € + extras
 * 4,00 €) under an Umsatz tile of 258,52 € — after 29,38 € of Rabatte and including a 4,00 € owner tip.
 *
 * A category is now what its lines were charged (finalPrice: extras in, manual line discount out)
 * less the item promotions granted on them. The bill-wide promotions and the owner tips, which belong
 * to no line, come back once as reconciliation figures, and the three add up to the overview's Umsatz.
 */
@DisplayName("Katalog categories reconcile with the Umsatz tile")
class StatisticsCatalogReconciliationTest {
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))

    private val today = currentTimeStamp().date
    private val rangeStart = today.minus(1, DateTimeUnit.DAY).atTime(0, 0, 0)
    private val rangeEnd = today.plus(1, DateTimeUnit.DAY).atTime(23, 59, 59)

    private class Sale(
        val gross: String,
        val promotion: String = "0",
        val discount: String = "0",
        val tips: String = "0",
        val recipient: TipRecipient = TipRecipient.EMPLOYEE,
    )

    private fun seed() {
        val admin = TestData.createAdmin()
        val drinks = TestData.createCategory(name = "Drinks")
        val food = TestData.createCategory(name = "Food")
        val cola = TestData.createProduct(categoryId = drinks.id.value, productName = "Cola", productNumber = "REC-COLA", price = BigDecimal("4.5000"))
        val pasta = TestData.createProduct(categoryId = food.id.value, productName = "Nudeln", productNumber = "REC-PASTA", price = BigDecimal("12.0000"))
        val vat = TestData.createDefaultVatRates().first { it.vatRate == BigDecimal("0.1900") }
        val cash = TestData.createPaymentType(name = "Cash")
        val tws = TestData.createTableWithSession("RECON")
        val billTime = today.atTime(12, 0, 0)

        transaction {
            val session = tws.session

            fun promotion(
                name: String,
                scope: PromotionScope,
            ) = PromotionTable.insert {
                it[PromotionTable.name] = name
                it[discountType] = DiscountType.FIXED_AMOUNT
                it[discountValue] = BigDecimal("1.0000")
                it[PromotionTable.scope] = scope
                it[validFrom] = billTime
                it[createdBy] = admin.id
                it[createdAt] = billTime
            } get PromotionTable.id
            val itemPromo = promotion("Nudel-Aktion", PromotionScope.ITEM_SPECIFIC)
            val billPromo = promotion("Abendrabatt", PromotionScope.BILL_WIDE)

            fun bill(
                number: String,
                sale: Sale,
            ): Bill {
                val paid = BigDecimal(sale.gross) - BigDecimal(sale.promotion) + BigDecimal(sale.tips)
                val bill =
                    Bill.new {
                        this.employeeId = session.employeeId
                        this.tableSession = session
                        this.billNumber = number
                        this.billAmount = BigDecimal(sale.gross)
                        this.promotionAmount = BigDecimal(sale.promotion)
                        this.discountAmount = BigDecimal(sale.discount)
                        this.tips = BigDecimal(sale.tips)
                        this.tipRecipient = sale.recipient
                        this.paidAmount = paid
                        this.startTime = billTime
                        this.endTime = billTime
                        this.isTraining = false
                    }
                PaymentTable.insert {
                    it[billId] = bill.id
                    it[paymentTypeId] = cash.id
                    it[amount] = paid
                    it[paymentStatus] = PaymentStatus.COMPLETED
                    it[paymentTime] = billTime
                    it[currency] = "EUR"
                }
                return bill
            }

            fun line(
                bill: Bill,
                product: Product,
                unit: String,
                charged: String,
            ) = OrderItem.new {
                this.session = session
                this.bill = bill
                this.product = product
                this.unitPrice = BigDecimal(unit)
                this.finalPrice = BigDecimal(charged)
                this.itemStatus = ItemStatus.PAID
                this.vat = vat
                this.takeAway = false
            }

            // Bill A: Cola 4,50 + 0,50 extra = 5,00; Nudeln 12,00 with a 2,00 item promotion; a
            // 1,00 bill-wide promotion; a 4,00 OWNER tip. billAmount 17,00, promotions 3,00.
            val a = bill("REC-A", Sale(gross = "17.0000", promotion = "3.0000", tips = "4.0000", recipient = TipRecipient.OWNER))
            line(a, cola, unit = "4.5000", charged = "5.0000")
            val pastaLine = line(a, pasta, unit = "12.0000", charged = "12.0000")
            BillPromotionTable.insert {
                it[billId] = a.id
                it[promotionId] = itemPromo
                it[orderItemId] = pastaLine.id
                it[deductedAmount] = BigDecimal("2.0000")
                it[appliedBy] = admin.id
                it[appliedAt] = billTime
            }
            BillPromotionTable.insert {
                it[billId] = a.id
                it[promotionId] = billPromo
                it[deductedAmount] = BigDecimal("1.0000")
                it[appliedBy] = admin.id
                it[appliedAt] = billTime
            }

            // Bill B: Cola at a 50 % manual line discount (2,25 charged) and a 2,00 EMPLOYEE tip,
            // which is not turnover.
            val b = bill("REC-B", Sale(gross = "2.2500", discount = "2.2500", tips = "2.0000"))
            line(b, cola, unit = "4.5000", charged = "2.2500")
        }
    }

    private fun assertMoney(
        expected: String,
        actual: String,
        message: String,
    ) = assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual)), "$message (expected $expected, got $actual)")

    @Test
    fun `categories are charged revenue net of item promotions and reconcile with the Umsatz tile`() =
        testApp(setupData = { seed() }) { _ ->
            transaction {
                val categories = repo.getCategoryPerformance(rangeStart, rangeEnd, "EUR", trainingOnly = false)
                val byName = categories.categoryMetrics.associateBy { it.categoryName }
                assertMoney("7.25", byName.getValue("Drinks").totalRevenue, "5,00 incl. extra + 2,25 after the line discount")
                assertMoney("10.00", byName.getValue("Food").totalRevenue, "12,00 less its 2,00 item promotion")
                assertMoney("1.00", categories.billWidePromotions, "only the promotion that sits on no line")
                assertMoney("4.00", categories.ownerTips, "the owner tip; the employee tip is not turnover")
                assertMoney("20.25", categories.reconciledRevenue, "17,25 − 1,00 + 4,00")

                val overview = repo.getSalesOverviewSummary(rangeStart, rangeEnd, "EUR", null, false)
                assertMoney(overview.totalRevenue, categories.reconciledRevenue, "the column adds up to the Umsatz tile")
            }
        }

    @Test
    fun `product revenue is the same net figure the categories regroup`() =
        testApp(setupData = { seed() }) { _ ->
            transaction {
                val products = repo.getBestSellers(rangeStart, rangeEnd, "EUR", trainingOnly = false)
                assertEquals(listOf("Nudeln", "Cola"), products.topProductsByRevenue.map { it.productName }, "ranked by net revenue")
                assertMoney("10.00", products.topProductsByRevenue[0].revenue, "Nudeln")
                assertMoney("7.25", products.topProductsByRevenue[1].revenue, "Cola")
                assertEquals(listOf("Cola", "Nudeln"), products.topProductsByQuantity.map { it.productName }, "2 Cola before 1 Nudeln")
                assertEquals(2, products.uniqueProductsSold)
            }
        }
}
