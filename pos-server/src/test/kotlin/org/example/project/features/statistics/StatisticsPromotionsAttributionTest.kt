package org.example.project.features.statistics

import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.example.project.features.promotion.database.tables.PromotionTable
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.statistics.data.repository.StatisticsRepositoryImpl
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that the Aktionen aggregation (`getPromotionBreakdown`) attributes bill_promotion rows by the
 * Z-Bon that booked the bill — not by the bill's own timestamp and not by `appliedAt` — and that
 * multiple applications of one promotion on the same bill group into a single line.
 *
 * Same fixture shape as the Katalog attribution test: a bill rung at 23:30 on May 31 whose Z-Bon
 * closes on June 1 is June 1's business, so its promotion rows must NOT appear in May.
 */
@DisplayName("Promotions Z-Bon attribution + grouping")
class StatisticsPromotionsAttributionTest {
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))

    private val zbonStart = LocalDate(2026, 5, 31).atTime(23, 0, 0)
    private val zbonEnd = LocalDate(2026, 6, 1).atTime(22, 0, 0)

    private val mayStart = LocalDate(2026, 5, 1).atTime(0, 0, 0)
    private val mayEnd = LocalDate(2026, 5, 31).atTime(23, 59, 59)
    private val juneStart = LocalDate(2026, 6, 1).atTime(0, 0, 0)
    private val juneEnd = LocalDate(2026, 6, 30).atTime(23, 59, 59)

    private fun seedPromotionFixture() {
        val admin = TestData.createAdmin()
        val shop = TestData.createShopInfo()
        val tws = TestData.createTableWithSession("PROMO")
        val zbon =
            TestData.createZBon(
                shop = shop,
                employee = admin,
                start = zbonStart,
                end = zbonEnd,
                nummer = 1L,
            )

        transaction {
            val session = tws.session
            val bill =
                Bill.new {
                    this.employeeId = session.employeeId
                    this.tableSession = session
                    this.billNumber = "PROMO-1"
                    this.billAmount = BigDecimal("20.0000")
                    this.paidAmount = BigDecimal("20.0000")
                    this.startTime = LocalDate(2026, 5, 31).atTime(23, 30, 0)
                    this.endTime = LocalDate(2026, 5, 31).atTime(23, 30, 0)
                    this.isTraining = false
                    this.zBon = zbon
                }

            val happyHourId =
                PromotionTable.insert {
                    it[name] = "Happy Hour -20%"
                    it[discountType] = DiscountType.PERCENTAGE
                    it[discountValue] = BigDecimal("20.0000")
                    it[scope] = PromotionScope.BILL_WIDE
                    it[validFrom] = LocalDate(2026, 1, 1).atTime(0, 0, 0)
                    it[createdBy] = admin.id
                    it[createdAt] = LocalDate(2026, 1, 1).atTime(0, 0, 0)
                } get PromotionTable.id

            val freeDrinkId =
                PromotionTable.insert {
                    it[name] = "Free drink"
                    it[discountType] = DiscountType.FREE_ITEM
                    it[discountValue] = BigDecimal("3.5000")
                    it[scope] = PromotionScope.ITEM_SPECIFIC
                    it[validFrom] = LocalDate(2026, 1, 1).atTime(0, 0, 0)
                    it[createdBy] = admin.id
                    it[createdAt] = LocalDate(2026, 1, 1).atTime(0, 0, 0)
                } get PromotionTable.id

            // Two applications of the same promotion on one bill (bill_promotion row per application).
            BillPromotionTable.insert {
                it[billId] = bill.id
                it[promotionId] = happyHourId
                it[deductedAmount] = BigDecimal("4.0000")
                it[appliedBy] = admin.id
                it[appliedAt] = LocalDate(2026, 5, 31).atTime(23, 30, 0)
            }
            BillPromotionTable.insert {
                it[billId] = bill.id
                it[promotionId] = happyHourId
                it[deductedAmount] = BigDecimal("4.0000")
                it[appliedBy] = admin.id
                it[appliedAt] = LocalDate(2026, 5, 31).atTime(23, 30, 0)
            }
            BillPromotionTable.insert {
                it[billId] = bill.id
                it[promotionId] = freeDrinkId
                it[deductedAmount] = BigDecimal("3.5000")
                it[appliedBy] = admin.id
                it[appliedAt] = LocalDate(2026, 5, 31).atTime(23, 30, 0)
            }
        }
    }

    private fun assertMoney(
        expected: String,
        actual: String,
        message: String,
    ) = assertEquals(
        0,
        BigDecimal(expected).compareTo(BigDecimal(actual)),
        "$message (expected $expected, got $actual)",
    )

    @Test
    fun `promotion rows book to the Z-Bon day and group per promotion`() =
        testApp(
            setupData = { seedPromotionFixture() },
        ) { _ ->
            transaction {
                // May must NOT claim the late bill's promotions: its Z-Bon closed on June 1.
                val mayPromotions = repo.getPromotionBreakdown(mayStart, mayEnd, "EUR", trainingOnly = false)
                assertTrue(
                    mayPromotions.isEmpty(),
                    "May must not claim promotions from a bill whose Z-Bon closed in June",
                )

                // June owns the whole Z-Bon: one line per promotion, grouped.
                val junePromotions = repo.getPromotionBreakdown(juneStart, juneEnd, "EUR", trainingOnly = false)
                assertEquals(2, junePromotions.size, "one row per promotion")

                val happyHour = junePromotions.single { it.name == "Happy Hour -20%" }
                assertEquals(DiscountType.PERCENTAGE.name, happyHour.discountType)
                assertEquals(2L, happyHour.timesApplied, "two bill_promotion rows for the same promotion")
                assertMoney("8.00", happyHour.totalDeducted, "Σ 4.00 + 4.00")

                val freeDrink = junePromotions.single { it.name == "Free drink" }
                assertEquals(DiscountType.FREE_ITEM.name, freeDrink.discountType)
                assertEquals(1L, freeDrink.timesApplied)
                assertMoney("3.50", freeDrink.totalDeducted, "Σ 3.50")
            }
        }
}
