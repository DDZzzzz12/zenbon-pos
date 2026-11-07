package org.example.project.features.statistics

import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.order.database.tables.ItemExtrasTable
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.product.domain.models.ProductExtras
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
import kotlin.test.assertTrue

/**
 * Pins that the Katalog aggregations (`getBestSellers`, `getCategoryPerformance`,
 * `getExtrasBreakdown`) attribute their product lines, category totals AND extras by the Z-Bon
 * that booked the bill — not by each bill's own wall-clock timestamp — and that the catalog product
 * revenue reconciles with the financial overview's gross revenue for the same window.
 *
 * A German restaurant cashes up once a day into a Z-Bon (fiscal daily close). A Z-Bon may straddle a
 * calendar boundary: a bill rung at 23:30 on day D whose Z-Bon closes on D+1 is fiscally D+1's
 * business — its products, its category, and its chosen extras all belong to D+1.
 */
@DisplayName("Catalog Z-Bon attribution + reconciliation")
class StatisticsCatalogAttributionTest {
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))

    // A Z-Bon whose window straddles midnight: opened 2026-05-31 23:00, closed 2026-06-01 22:00.
    // Its close is on June 1, so every bill it booked (the 23:30 May-31 bill included) is June 1's
    // business.
    private val zbonStart = LocalDate(2026, 5, 31).atTime(23, 0, 0)
    private val zbonEnd = LocalDate(2026, 6, 1).atTime(22, 0, 0)

    private val mayStart = LocalDate(2026, 5, 1).atTime(0, 0, 0)
    private val mayEnd = LocalDate(2026, 5, 31).atTime(23, 59, 59)
    private val juneStart = LocalDate(2026, 6, 1).atTime(0, 0, 0)
    private val juneEnd = LocalDate(2026, 6, 30).atTime(23, 59, 59)

    /**
     * Seeds one Z-Bon (straddling midnight, closing June 1), one bill rung at 23:30 on May 31 and
     * attached to that Z-Bon, its cash payment of 24.00, two "Cola" (12.00 each) order items, and
     * three snapshotted extras rows across two options.
     */
    private fun seedCatalogFixture() {
        val admin = TestData.createAdmin()
        val shop = TestData.createShopInfo()
        val category = TestData.createCategory(name = "Drinks")
        val cola =
            TestData.createProduct(
                categoryId = category.id.value,
                productName = "Cola",
                productNumber = "CAT-COLA",
                price = BigDecimal("12.0000"),
            )
        val vat = TestData.createDefaultVatRates().first { it.vatRate == BigDecimal("0.1900") }
        val cash = TestData.createPaymentType(name = "Cash")
        val tws = TestData.createTableWithSession("CATATTR")
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

            // Rung 2026-05-31 23:30 — its OWN timestamp is May, but its Z-Bon closes June 1.
            val bill =
                Bill.new {
                    this.employeeId = session.employeeId
                    this.tableSession = session
                    this.billNumber = "CAT-LATE"
                    this.billAmount = BigDecimal("24.0000")
                    this.paidAmount = BigDecimal("24.0000")
                    this.startTime = LocalDate(2026, 5, 31).atTime(23, 30, 0)
                    this.endTime = LocalDate(2026, 5, 31).atTime(23, 30, 0)
                    this.isTraining = false
                    this.zBon = zbon
                }
            PaymentTable.insert {
                it[billId] = bill.id
                it[paymentTypeId] = cash.id
                it[amount] = BigDecimal("24.0000")
                it[paymentStatus] = PaymentStatus.COMPLETED
                it[paymentTime] = LocalDate(2026, 5, 31).atTime(23, 30, 0)
                it[currency] = "EUR"
            }

            val item1 =
                OrderItem.new {
                    this.session = session
                    this.bill = bill
                    this.product = cola
                    this.unitPrice = BigDecimal("12.0000")
                    this.finalPrice = BigDecimal("12.0000")
                    this.itemStatus = ItemStatus.PAID
                    this.vat = vat
                    this.takeAway = false
                }
            val item2 =
                OrderItem.new {
                    this.session = session
                    this.bill = bill
                    this.product = cola
                    this.unitPrice = BigDecimal("12.0000")
                    this.finalPrice = BigDecimal("12.0000")
                    this.itemStatus = ItemStatus.PAID
                    this.vat = vat
                    this.takeAway = false
                }

            // Extras: "Extra shot" chosen 2× on item1 + 3× on item2 => timesChosen 5, added revenue
            // Σ(1.50 × qty) = 1.50*2 + 1.50*3 = 7.50. "Oat milk" chosen 1× on item1 => 1 / 0.80.
            ItemExtrasTable.insert {
                it[orderItemId] = item1.id
                it[optionNameSnapshot] = "Extra shot"
                it[groupNameSnapshot] = "Coffee"
                it[quantity] = 2
                it[priceDeltaSnapshot] = BigDecimal("1.5000")
            }
            ItemExtrasTable.insert {
                it[orderItemId] = item2.id
                it[optionNameSnapshot] = "Extra shot"
                it[groupNameSnapshot] = "Coffee"
                it[quantity] = 3
                it[priceDeltaSnapshot] = BigDecimal("1.5000")
            }
            ItemExtrasTable.insert {
                it[orderItemId] = item1.id
                it[optionNameSnapshot] = "Oat milk"
                it[groupNameSnapshot] = "Coffee"
                it[quantity] = 1
                it[priceDeltaSnapshot] = BigDecimal("0.8000")
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
    fun `product lines and category totals book to the Z-Bon day and reconcile with the overview`() =
        testApp(
            setupData = { seedCatalogFixture() },
        ) { _ ->
            transaction {
                // May must NOT claim the late bill: its Z-Bon closed on June 1.
                val mayProducts = repo.getBestSellers(mayStart, mayEnd, "EUR", trainingOnly = false)
                assertTrue(
                    mayProducts.topProductsByRevenue.isEmpty(),
                    "May must not claim products from a bill whose Z-Bon closed in June",
                )
                val mayCategories = repo.getCategoryPerformance(mayStart, mayEnd, "EUR", trainingOnly = false)
                assertTrue(mayCategories.categoryMetrics.isEmpty(), "and no category totals")

                // June owns the whole Z-Bon.
                val juneProducts = repo.getBestSellers(juneStart, juneEnd, "EUR", trainingOnly = false)
                val cola = juneProducts.topProductsByRevenue.single()
                assertEquals("Cola", cola.productName, "June owns the late bill's product line")
                assertEquals(2, cola.quantitySold, "two Cola order-item rows")
                assertMoney("24.00", cola.revenue, "product revenue = Σ finalPrice")

                val juneCategories = repo.getCategoryPerformance(juneStart, juneEnd, "EUR", trainingOnly = false)
                val drinks = juneCategories.categoryMetrics.single()
                assertEquals("Drinks", drinks.categoryName)
                assertMoney("24.00", drinks.totalRevenue, "category total = Σ finalPrice of its products")

                // Reconciliation: the catalog's product revenue equals the financial overview's gross for
                // the same window and attribution (payment total = 24.00).
                val overview = repo.getFinancialOverview(juneStart, juneEnd, "EUR", null, false)
                assertMoney(
                    overview.grossRevenue,
                    cola.revenue,
                    "catalog product revenue must reconcile with the overview gross for the same window",
                )
            }
        }

    @Test
    fun `getExtrasBreakdown sums quantity and priceDelta times quantity per option and attributes by Z-Bon`() =
        testApp(
            setupData = { seedCatalogFixture() },
        ) { _ ->
            transaction {
                // Absent in May (booked to its Z-Bon's June close).
                val mayExtras = repo.getExtrasBreakdown(mayStart, mayEnd, "EUR", trainingOnly = false)
                assertTrue(mayExtras.isEmpty(), "extras attribute to the Z-Bon day, not the bill's May timestamp")

                val juneExtras = repo.getExtrasBreakdown(juneStart, juneEnd, "EUR", trainingOnly = false)
                assertEquals(2, juneExtras.size, "one row per option")

                val extraShot = juneExtras.single { it.optionName == "Extra shot" }
                assertEquals("Coffee", extraShot.groupName)
                assertEquals(5L, extraShot.timesChosen, "Σ quantity = 2 + 3")
                assertMoney("7.50", extraShot.addedRevenue, "Σ(priceDelta × quantity) = 1.50*2 + 1.50*3")

                val oatMilk = juneExtras.single { it.optionName == "Oat milk" }
                assertEquals(1L, oatMilk.timesChosen)
                assertMoney("0.80", oatMilk.addedRevenue, "Σ(priceDelta × quantity) = 0.80*1")
            }
        }

    /**
     * Legacy rows predate the snapshot columns: optionNameSnapshot = "" and groupNameSnapshot = null,
     * but they still carry product_extras_id. The breakdown must fall back to the live ProductExtras
     * name so those rows don't collapse into a blank-named bucket, and a legacy row must MERGE with a
     * snapshotted row of the same option into one line with the combined revenue.
     */
    @Test
    fun `legacy rows with empty snapshot fall back to the live extra name and merge with snapshotted rows`() =
        testApp(
            setupData = {
                val category = TestData.createCategory(name = "Drinks")
                val cola =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "Cola",
                        productNumber = "CAT-LEG",
                        price = BigDecimal("12.0000"),
                    )
                val vat = TestData.createDefaultVatRates().first { it.vatRate == BigDecimal("0.1900") }
                val tws = TestData.createTableWithSession("CATLEG")
                transaction {
                    val session = tws.session
                    val syrup =
                        ProductExtras.new {
                            this.name = "Vanilla syrup"
                            this.price = BigDecimal("0.5000")
                            this.isActive = true
                            this.updatedAt = currentTimeStamp()
                        }
                    // An OPEN bill (no Z-Bon) — attributed to "now", which is inside a wide window below.
                    val bill =
                        Bill.new {
                            this.employeeId = session.employeeId
                            this.tableSession = session
                            this.billNumber = "CAT-LEG-1"
                            this.billAmount = BigDecimal("12.0000")
                            this.paidAmount = BigDecimal("12.0000")
                            this.startTime = currentTimeStamp()
                            this.endTime = currentTimeStamp()
                            this.isTraining = false
                        }
                    val item =
                        OrderItem.new {
                            this.session = session
                            this.bill = bill
                            this.product = cola
                            this.unitPrice = BigDecimal("12.0000")
                            this.finalPrice = BigDecimal("12.0000")
                            this.itemStatus = ItemStatus.PAID
                            this.vat = vat
                            this.takeAway = false
                        }
                    // Legacy row: empty snapshot (defaults), only product_extras_id set.
                    ItemExtrasTable.insert {
                        it[orderItemId] = item.id
                        it[productExtrasId] = syrup.id
                        it[quantity] = 2
                        it[priceDeltaSnapshot] = BigDecimal("0.5000")
                    }
                    // Snapshotted row for the SAME option (name matches the live extra).
                    ItemExtrasTable.insert {
                        it[orderItemId] = item.id
                        it[productExtrasId] = syrup.id
                        it[optionNameSnapshot] = "Vanilla syrup"
                        it[quantity] = 1
                        it[priceDeltaSnapshot] = BigDecimal("0.5000")
                    }
                }
            },
        ) { _ ->
            transaction {
                val wide =
                    repo.getExtrasBreakdown(
                        LocalDate(2020, 1, 1).atTime(0, 0, 0),
                        LocalDate(2100, 1, 1).atTime(0, 0, 0),
                        "EUR",
                        trainingOnly = false,
                    )
                val syrup = wide.single()
                assertEquals(
                    "Vanilla syrup",
                    syrup.optionName,
                    "legacy empty snapshot resolves to the live name and merges with the snapshotted row",
                )
                assertEquals(3L, syrup.timesChosen, "legacy 2 + snapshotted 1")
                assertMoney("1.50", syrup.addedRevenue, "Σ(0.50 × 2) + Σ(0.50 × 1)")
            }
        }
}
