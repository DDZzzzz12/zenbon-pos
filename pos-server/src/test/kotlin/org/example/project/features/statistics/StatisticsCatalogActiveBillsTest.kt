package org.example.project.features.statistics

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.bill.StornoReason
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.order.database.tables.ItemExtrasTable
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.database.tables.PaymentTable
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
 * admin16 FAIL-3 / FAIL-4: the Berichte "Aktionen" and "Katalog" sections counted the promotions and
 * the product lines of every bill in the window — a cancelled original included. A Zahlartwechsel
 * therefore counted its items twice (original + Neubuchung) and an ordinary Storno's items once
 * although the sale no longer stands; the live day showed 28 Cola for 15 on standing receipts and
 * categories summing to 430,30 € on a 258,52 € day.
 *
 * Both now count ACTIVE bills only (`activeBillOp`, the SQL twin of the Z-Bon's
 * `DrawerCashDerivation.activeBills`): neither a Storno nor a cancelled original. The Neubuchung
 * counts, so a Zahlartwechsel's items and promotions count exactly once.
 *
 * Seeded as BillServiceImpl writes them: a Storno carries no order items (they are duplicated onto
 * the Neubuchung), points `deletedBill` at the original, and the original gets `deletingReason`.
 */
@DisplayName("Katalog + Aktionen count active bills only")
class StatisticsCatalogActiveBillsTest {
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))

    private val today = currentTimeStamp().date
    private val rangeStart = today.minus(1, DateTimeUnit.DAY).atTime(0, 0, 0)
    private val rangeEnd = today.plus(1, DateTimeUnit.DAY).atTime(23, 59, 59)

    private fun seed() {
        val admin = TestData.createAdmin()
        val category = TestData.createCategory(name = "Drinks")
        val cola =
            TestData.createProduct(
                categoryId = category.id.value,
                productName = "Cola",
                productNumber = "ACT-COLA",
                price = BigDecimal("4.5000"),
            )
        val vat = TestData.createDefaultVatRates().first { it.vatRate == BigDecimal("0.1900") }
        val cash = TestData.createPaymentType(name = "Cash")
        val tws = TestData.createTableWithSession("ACTIVE")
        val billTime = today.atTime(12, 0, 0)

        transaction {
            val session = tws.session
            val promoId =
                PromotionTable.insert {
                    it[name] = "Abendrabatt"
                    it[discountType] = DiscountType.FIXED_AMOUNT
                    it[discountValue] = BigDecimal("1.0000")
                    it[scope] = PromotionScope.BILL_WIDE
                    it[validFrom] = billTime
                    it[createdBy] = admin.id
                    it[createdAt] = billTime
                } get PromotionTable.id

            fun bill(
                number: String,
                gross: String,
                promotion: String = "0",
            ): Bill {
                val bill =
                    Bill.new {
                        this.employeeId = session.employeeId
                        this.tableSession = session
                        this.billNumber = number
                        this.billAmount = BigDecimal(gross)
                        this.promotionAmount = BigDecimal(promotion)
                        this.paidAmount = BigDecimal(gross) - BigDecimal(promotion)
                        this.startTime = billTime
                        this.endTime = billTime
                        this.isTraining = false
                    }
                PaymentTable.insert {
                    it[billId] = bill.id
                    it[paymentTypeId] = cash.id
                    it[amount] = BigDecimal(gross) - BigDecimal(promotion)
                    it[paymentStatus] = PaymentStatus.COMPLETED
                    it[paymentTime] = billTime
                    it[currency] = "EUR"
                }
                return bill
            }

            fun colas(
                bill: Bill,
                count: Int,
            ) = repeat(count) {
                val item =
                    OrderItem.new {
                        this.session = session
                        this.bill = bill
                        this.product = cola
                        this.unitPrice = BigDecimal("4.5000")
                        this.finalPrice = BigDecimal("4.5000")
                        this.itemStatus = ItemStatus.PAID
                        this.vat = vat
                        this.takeAway = false
                    }
                ItemExtrasTable.insert {
                    it[orderItemId] = item.id
                    it[optionNameSnapshot] = "Eis"
                    it[groupNameSnapshot] = "Extras"
                    it[quantity] = 1
                    it[priceDeltaSnapshot] = BigDecimal("0.0000")
                }
            }

            fun promotion(
                bill: Bill,
                amount: String,
            ) = BillPromotionTable.insert {
                it[billId] = bill.id
                it[promotionId] = promoId
                it[deductedAmount] = BigDecimal(amount)
                it[appliedBy] = admin.id
                it[appliedAt] = billTime
            }

            // Standing sale: 2 Cola, 1,00 € promotion — counts.
            val standing = bill("ACT-1", "9.0000", promotion = "1.0000")
            colas(standing, 2)
            promotion(standing, "1.0000")

            // Ordinary Storno: 3 Cola + 1,00 € promotion on a sale that no longer stands — nothing counts.
            val stornoed = bill("ACT-2", "13.5000", promotion = "1.0000")
            colas(stornoed, 3)
            promotion(stornoed, "1.0000")
            bill("ACT-2-ST", "-12.5000").apply { deletedBill = stornoed }
            stornoed.deletingReason = "ACT-2-ST : falsch gebucht"

            // Zahlartwechsel: original (cancelled) + Storno drop out, the Neubuchung counts once.
            val original = bill("ACT-3", "4.5000", promotion = "1.0000")
            colas(original, 1)
            promotion(original, "1.0000")
            bill("ACT-3-ST", "-3.5000").apply {
                deletedBill = original
                stornoReason = StornoReason.ZAHLARTWECHSEL
            }
            original.deletingReason = "ACT-3-ST : Zahlartwechsel"
            val rebooking = bill("ACT-3-NEU", "4.5000", promotion = "1.0000").apply { rebooksBill = original }
            colas(rebooking, 1)
            promotion(rebooking, "1.0000")
        }
    }

    private fun assertMoney(
        expected: String,
        actual: String,
        message: String,
    ) = assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual)), "$message (expected $expected, got $actual)")

    @Test
    fun `Katalog counts the items of active bills only, a Zahlartwechsel once`() =
        testApp(setupData = { seed() }) { _ ->
            transaction {
                val cola =
                    repo
                        .getBestSellers(rangeStart, rangeEnd, "EUR", trainingOnly = false)
                        .topProductsByQuantity
                        .single()
                assertEquals(3, cola.quantitySold, "2 standing + 1 Neubuchung; the cancelled originals' 4 do not count")
                assertMoney("13.50", cola.revenue, "3 × 4,50")

                val drinks =
                    repo
                        .getCategoryPerformance(rangeStart, rangeEnd, "EUR", trainingOnly = false)
                        .categoryMetrics
                        .single()
                assertEquals(3, drinks.totalQuantitySold)
                assertMoney("13.50", drinks.totalRevenue, "category = the same product lines regrouped")

                // The category is what its lines were charged; the promotions here are bill-wide, so
                // they sit on no line and come back once as the reconciliation figure, taking the
                // column to the overview's takings (13,50 − 2,00 = 11,50).
                val categories = repo.getCategoryPerformance(rangeStart, rangeEnd, "EUR", trainingOnly = false)
                assertMoney("2.00", categories.billWidePromotions, "the active bills' bill-wide promotions only")
                val overview = repo.getFinancialOverview(rangeStart, rangeEnd, "EUR", null, false)
                assertMoney("11.50", overview.grossRevenue, "overview gross: active bills' takings")
                assertMoney(overview.grossRevenue, categories.reconciledRevenue, "categories reconcile with the overview")

                val extras = repo.getExtrasBreakdown(rangeStart, rangeEnd, "EUR", trainingOnly = false).single()
                assertEquals(3L, extras.timesChosen, "extras ride on the same active item lines")
            }
        }

    @Test
    fun `Aktionen counts the promotions of active bills only and matches the overview discounts`() =
        testApp(setupData = { seed() }) { _ ->
            transaction {
                val promotion = repo.getPromotionBreakdown(rangeStart, rangeEnd, "EUR", trainingOnly = false).single()
                assertEquals(2L, promotion.timesApplied, "standing sale + Neubuchung; neither cancelled original")
                assertMoney("2.00", promotion.totalDeducted, "1,00 + 1,00")

                val overview = repo.getSalesOverviewSummary(rangeStart, rangeEnd, "EUR", null, false)
                assertMoney(
                    overview.totalDiscounts,
                    promotion.totalDeducted,
                    "Aktionen and the overview's Rabatte (the Z-Bon rule) must agree when no line discount exists",
                )
            }
        }
}
