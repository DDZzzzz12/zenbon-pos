package org.example.project.features.statistics

import com.zaxxer.hikari.HikariConfig
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.database.DatabaseFactory
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.order.database.tables.ItemExtrasTable
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.statistics.data.repository.StatisticsRepositoryImpl
import org.example.project.infrastructure.plugins.initDB
import org.example.project.testutils.PostgresTestDatabase
import org.example.project.testutils.TestData
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.stopKoin
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Runs the three Katalog grouped queries — `getBestSellers`, `getCategoryPerformance`,
 * `getExtrasBreakdown` — against a real PostgreSQL (zonky embedded PG), not H2.
 *
 * Each groups by `COALESCE(zbon.end_time, now)`. Postgres enforces that a grouped SELECT expression
 * match the GROUP BY expression exactly, so if the `now` fallback were a bound parameter (a fresh
 * `$N` per occurrence) the COALESCEs would read as different expressions and Postgres would throw
 * "end_time must appear in the GROUP BY clause". H2 tolerates it. This test pins the catalog
 * aggregations on the real dialect: the assertion that matters is that each query *executes*.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Catalog grouped queries run on real PostgreSQL")
class StatisticsCatalogPostgresTest {
    private lateinit var pg: PostgresTestDatabase
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))

    private val day = LocalDate(2026, 6, 10)
    private val windowStart = LocalDate(2026, 6, 1).atTime(0, 0, 0)
    private val windowEnd = LocalDate(2026, 6, 30).atTime(23, 59, 59)

    @BeforeAll
    fun startDatabase() {
        pg = PostgresTestDatabase.start()
        stopKoin()
        if (DatabaseFactory.isInitialized()) DatabaseFactory.close()
        initDB(
            HikariConfig().apply {
                jdbcUrl = pg.jdbcUrl
                driverClassName = "org.postgresql.Driver"
                username = "postgres"
                password = "postgres"
                maximumPoolSize = 4
                isAutoCommit = false
            },
            runMigrations = false,
        )

        val admin = TestData.createAdmin()
        val shop = TestData.createShopInfo()
        val category = TestData.createCategory(name = "Drinks")
        val cola =
            TestData.createProduct(
                categoryId = category.id.value,
                productName = "Cola",
                productNumber = "PGCAT-COLA",
                price = BigDecimal("12.0000"),
            )
        val vat = TestData.createDefaultVatRates().first { it.vatRate == BigDecimal("0.1900") }
        val cash = TestData.createPaymentType(name = "Cash")
        val tws = TestData.createTableWithSession("PGCAT")
        val zbon =
            TestData.createZBon(
                shop = shop,
                employee = admin,
                start = day.atTime(0, 0, 0),
                end = day.atTime(23, 59, 59),
                nummer = 1L,
            )
        transaction {
            val session = tws.session
            val bill =
                Bill.new {
                    this.employeeId = session.employeeId
                    this.tableSession = session
                    this.billNumber = "PGCAT-1"
                    this.billAmount = BigDecimal("24.0000")
                    this.paidAmount = BigDecimal("24.0000")
                    this.startTime = day.atTime(12, 0, 0)
                    this.endTime = day.atTime(12, 0, 0)
                    this.isTraining = false
                    this.zBon = zbon
                }
            PaymentTable.insert {
                it[billId] = bill.id
                it[paymentTypeId] = cash.id
                it[amount] = BigDecimal("24.0000")
                it[paymentStatus] = PaymentStatus.COMPLETED
                it[paymentTime] = day.atTime(12, 0, 0)
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
        }
    }

    @AfterAll
    fun stopDatabase() {
        DatabaseFactory.close()
        pg.close()
    }

    @Test
    fun `getBestSellers grouped query executes and attributes by the Z-Bon day`() {
        val best =
            transaction {
                repo.getBestSellers(windowStart, windowEnd, "EUR", trainingOnly = false)
            }
        val cola = best.topProductsByRevenue.single()
        assertEquals("Cola", cola.productName)
        assertEquals(2, cola.quantitySold, "two Cola order-item rows in the Z-Bon window")
    }

    @Test
    fun `getCategoryPerformance grouped query executes and attributes by the Z-Bon day`() {
        val categories =
            transaction {
                repo.getCategoryPerformance(windowStart, windowEnd, "EUR", trainingOnly = false)
            }
        assertEquals("Drinks", categories.categoryMetrics.single().categoryName)
    }

    @Test
    fun `getExtrasBreakdown grouped query executes and attributes by the Z-Bon day`() {
        val extras =
            transaction {
                repo.getExtrasBreakdown(windowStart, windowEnd, "EUR", trainingOnly = false)
            }
        val extraShot = extras.single { it.optionName == "Extra shot" }
        assertEquals(5L, extraShot.timesChosen, "Σ quantity = 2 + 3")
    }
}
