package org.example.project.features.statistics

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atTime
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.product.domain.models.Product
import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.example.project.features.promotion.database.tables.PromotionTable
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.statistics.data.repository.StatisticsRepositoryImpl
import org.example.project.features.statistics.data.service.StatisticsServiceImpl
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the previous-window comparison contract of [StatisticsServiceImpl.getCatalogBreakdown] and
 * [StatisticsServiceImpl.getPromotionBreakdown]:
 *
 * - compare off (compareGranularity = null) -> every growthRate / delta field is null, never the
 *   repository's hardcoded "0.00" placeholder;
 * - a complete past WEEK window compares to the whole previous week (fixed 7-day shift) with
 *   hand-computed delta percents;
 * - a row with no previous-window baseline gets null, not a fabricated zero;
 * - promotions present in both windows get per-row prior-period deltas, absent ones stay null.
 *
 * Z-Bon attribution mirrors the sibling attribution tests: a bill is counted in the window its
 * Z-Bon CLOSED in, so each fixture window gets its own Z-Bon whose endTime sits inside it.
 */
@DisplayName("Statistics service previous-window comparison")
class StatisticsCompareServiceTest {
    private val service =
        StatisticsServiceImpl(
            statisticsRepository = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink())),
            configManager = currencyConfigProxy(),
        )

    private var adminId: String = ""

    // Complete past weeks (today is 2026-08-01, so neither window reaches into the current one).
    private val currentStart = LocalDate(2026, 6, 1).atTime(0, 0, 0)
    private val currentEnd = LocalDate(2026, 6, 7).atTime(23, 59, 59)
    private val previousStart = LocalDate(2026, 5, 25).atTime(0, 0, 0)
    private val previousEnd = LocalDate(2026, 5, 31).atTime(23, 59, 59)
    private val previousZbonEnd = LocalDate(2026, 5, 31).atTime(22, 0, 0)
    private val currentZbonEnd = LocalDate(2026, 6, 7).atTime(22, 0, 0)

    private fun currencyConfigProxy(): IShopConfigurationManager {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            IShopConfigurationManager::class.java.classLoader,
            arrayOf(IShopConfigurationManager::class.java),
        ) { _, method, _ ->
            if (method.name == "getDefaultCurrency") return@newProxyInstance "EUR"
            throw NotImplementedError("Stub: ${method.name} should not be called in compare tests")
        } as IShopConfigurationManager
    }

    private fun seedCompareFixture() {
        val admin = TestData.createAdmin()
        adminId = admin.id.value.toString()
        val shop = TestData.createShopInfo()
        val drinks = TestData.createCategory(name = "Drinks")
        val cola =
            TestData.createProduct(
                categoryId = drinks.id.value,
                productName = "Cola",
                productNumber = "CMP-COLA",
                price = BigDecimal("12.0000"),
            )
        val fanta =
            TestData.createProduct(
                categoryId = drinks.id.value,
                productName = "Fanta",
                productNumber = "CMP-FANTA",
                price = BigDecimal("8.0000"),
            )
        val vat = TestData.createDefaultVatRates().first { it.vatRate == BigDecimal("0.1900") }

        val happyHourId =
            transaction {
                PromotionTable.insert {
                    it[name] = "Happy Hour -20%"
                    it[discountType] = DiscountType.PERCENTAGE
                    it[discountValue] = BigDecimal("20.0000")
                    it[scope] = PromotionScope.BILL_WIDE
                    it[validFrom] = LocalDate(2026, 1, 1).atTime(0, 0, 0)
                    it[createdBy] = admin.id
                    it[createdAt] = LocalDate(2026, 1, 1).atTime(0, 0, 0)
                } get PromotionTable.id
            }
        val freeDrinkId =
            transaction {
                PromotionTable.insert {
                    it[name] = "Free drink"
                    it[discountType] = DiscountType.FREE_ITEM
                    it[discountValue] = BigDecimal("3.5000")
                    it[scope] = PromotionScope.ITEM_SPECIFIC
                    it[validFrom] = LocalDate(2026, 1, 1).atTime(0, 0, 0)
                    it[createdBy] = admin.id
                    it[createdAt] = LocalDate(2026, 1, 1).atTime(0, 0, 0)
                } get PromotionTable.id
            }

        val prevZbon = TestData.createZBon(shop, admin, previousStart, previousZbonEnd, nummer = 1L)
        val curZbon = TestData.createZBon(shop, admin, currentStart, currentZbonEnd, nummer = 2L)

        seedWindow(
            admin = admin,
            zbon = prevZbon,
            billNumber = "CMP-PREV",
            billAmount = BigDecimal("12.0000"),
            colaQty = 1,
            fantaQty = 0,
            happyHourApps = 1,
            freeDrinkApps = 0,
            vat = vat,
            cola = cola,
            fanta = fanta,
            happyHourId = happyHourId,
            freeDrinkId = freeDrinkId,
            billTime = LocalDate(2026, 5, 31).atTime(18, 0, 0),
        )
        seedWindow(
            admin = admin,
            zbon = curZbon,
            billNumber = "CMP-CURR",
            billAmount = BigDecimal("32.0000"),
            colaQty = 2,
            fantaQty = 1,
            happyHourApps = 2,
            freeDrinkApps = 1,
            vat = vat,
            cola = cola,
            fanta = fanta,
            happyHourId = happyHourId,
            freeDrinkId = freeDrinkId,
            billTime = LocalDate(2026, 6, 7).atTime(18, 0, 0),
        )
    }

    private fun seedWindow(
        admin: Employee,
        zbon: ZBon,
        billNumber: String,
        billAmount: BigDecimal,
        colaQty: Int,
        fantaQty: Int,
        happyHourApps: Int,
        freeDrinkApps: Int,
        vat: Vat,
        cola: Product,
        fanta: Product,
        happyHourId: EntityID<Long>,
        freeDrinkId: EntityID<Long>,
        billTime: LocalDateTime,
    ) {
        val tws = TestData.createTableWithSession(billNumber)
        transaction {
            val session = tws.session
            val bill =
                Bill.new {
                    this.employeeId = session.employeeId
                    this.tableSession = session
                    this.billNumber = billNumber
                    this.billAmount = billAmount
                    this.paidAmount = billAmount
                    this.startTime = billTime
                    this.endTime = billTime
                    this.isTraining = false
                    this.zBon = zbon
                }
            repeat(colaQty) {
                OrderItem.new {
                    this.session = session
                    this.bill = bill
                    this.product = cola
                    this.unitPrice = cola.price
                    this.finalPrice = cola.price
                    this.itemStatus = ItemStatus.PAID
                    this.vat = vat
                    this.takeAway = false
                }
            }
            repeat(fantaQty) {
                OrderItem.new {
                    this.session = session
                    this.bill = bill
                    this.product = fanta
                    this.unitPrice = fanta.price
                    this.finalPrice = fanta.price
                    this.itemStatus = ItemStatus.PAID
                    this.vat = vat
                    this.takeAway = false
                }
            }
            repeat(happyHourApps) {
                BillPromotionTable.insert {
                    it[billId] = bill.id
                    it[promotionId] = happyHourId
                    it[deductedAmount] = BigDecimal("4.0000")
                    it[appliedBy] = admin.id
                    it[appliedAt] = billTime
                }
            }
            repeat(freeDrinkApps) {
                BillPromotionTable.insert {
                    it[billId] = bill.id
                    it[promotionId] = freeDrinkId
                    it[deductedAmount] = BigDecimal("3.5000")
                    it[appliedBy] = admin.id
                    it[appliedAt] = billTime
                }
            }
        }
    }

    @Test
    fun `compare off returns null growth and delta fields instead of hardcoded placeholders`() =
        testApp(
            setupData = { seedCompareFixture() },
        ) { _ ->
            val catalog =
                service.getCatalogBreakdown(
                    currentStart,
                    currentEnd,
                    adminId,
                    filterEmployeeId = null,
                    trainingOnly = false,
                    compareGranularity = null,
                )
            catalog.bestSellers.topProductsByRevenue.forEach {
                assertNull(it.growthRate, "product ${it.productName} growth must be null with compare off")
            }
            catalog.bestSellers.topProductsByQuantity.forEach {
                assertNull(it.growthRate, "product ${it.productName} quantity-list growth must be null with compare off")
            }
            catalog.categories.categoryMetrics.forEach {
                assertNull(it.growthRate, "category ${it.categoryName} growth must be null with compare off")
            }

            val promotions =
                service.getPromotionBreakdown(
                    currentStart,
                    currentEnd,
                    adminId,
                    filterEmployeeId = null,
                    trainingOnly = false,
                    compareGranularity = null,
                )
            promotions.promotions.forEach {
                assertNull(it.timesAppliedDeltaPercent, "promotion ${it.name} times delta must be null with compare off")
                assertNull(it.totalDeductedDeltaPercent, "promotion ${it.name} deducted delta must be null with compare off")
            }
        }

    @Test
    fun `WEEK compare on a complete past window attaches hand-computed growth deltas`() =
        testApp(
            setupData = { seedCompareFixture() },
        ) { _ ->
            val catalog =
                service.getCatalogBreakdown(
                    currentStart,
                    currentEnd,
                    adminId,
                    filterEmployeeId = null,
                    trainingOnly = false,
                    compareGranularity = "WEEK",
                )

            val cola = catalog.bestSellers.topProductsByRevenue.single { it.productName == "Cola" }
            assertEquals("100.00", cola.growthRate, "Cola 24.00 vs previous 12.00")

            val drinks = catalog.categories.categoryMetrics.single { it.categoryName == "Drinks" }
            assertEquals("166.67", drinks.growthRate, "Drinks 32.00 vs previous 12.00")

            catalog.bestSellers.topProductsByQuantity.forEach {
                assertNull(it.growthRate, "quantity list has no baseline merge and must stay null")
            }
        }

    @Test
    fun `product without a previous-window baseline gets null growth not a fabricated zero`() =
        testApp(
            setupData = { seedCompareFixture() },
        ) { _ ->
            val catalog =
                service.getCatalogBreakdown(
                    currentStart,
                    currentEnd,
                    adminId,
                    filterEmployeeId = null,
                    trainingOnly = false,
                    compareGranularity = "WEEK",
                )

            val fanta = catalog.bestSellers.topProductsByRevenue.single { it.productName == "Fanta" }
            assertNull(fanta.growthRate, "Fanta has no previous-window row -> null, never 0.00")
        }

    @Test
    fun `promotions present in both windows get prior-period deltas and absent ones stay null`() =
        testApp(
            setupData = { seedCompareFixture() },
        ) { _ ->
            val result =
                service.getPromotionBreakdown(
                    currentStart,
                    currentEnd,
                    adminId,
                    filterEmployeeId = null,
                    trainingOnly = false,
                    compareGranularity = "WEEK",
                )

            val happyHour = result.promotions.single { it.name == "Happy Hour -20%" }
            assertEquals("100.00", happyHour.timesAppliedDeltaPercent, "2 applications vs previous 1")
            assertEquals("100.00", happyHour.totalDeductedDeltaPercent, "8.00 vs previous 4.00")

            val freeDrink = result.promotions.single { it.name == "Free drink" }
            assertNull(freeDrink.timesAppliedDeltaPercent, "no previous-window row -> null")
            assertNull(freeDrink.totalDeductedDeltaPercent, "no previous-window row -> null")
        }
}
