package org.example.project.features.statistics

import com.zaxxer.hikari.HikariConfig
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.database.DatabaseFactory
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.database.tables.BillVatDetailTable
import org.example.project.features.bill.domain.models.Bill
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
 * Runs `getFinancialOverview` against a real PostgreSQL (zonky embedded PG), not H2.
 *
 * The Z-Bon attribution groups by `COALESCE(zbon.end_time, now)`. Postgres enforces that a grouped
 * SELECT expression match the GROUP BY expression exactly, so if the `now` fallback is a bound
 * parameter (a fresh `$N` per occurrence) the two COALESCEs read as different expressions and
 * Postgres throws "end_time must appear in the GROUP BY clause". H2 tolerates it. This test pins
 * the financial aggregation on the real dialect.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("StatisticsRepositoryImpl.getFinancialOverview runs on real PostgreSQL")
class StatisticsFinancialPostgresTest {
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
        val vat = TestData.createDefaultVatRates().first { it.vatRate == BigDecimal("0.1900") }
        val cash = TestData.createPaymentType(name = "Cash")
        val tws = TestData.createTableWithSession("PGFIN")
        val zbon =
            TestData.createZBon(
                shop = shop,
                employee = admin,
                start = day.atTime(0, 0, 0),
                end = day.atTime(23, 59, 59),
                nummer = 1L,
            )
        transaction {
            val bill =
                Bill.new {
                    this.employeeId = tws.session.employeeId
                    this.tableSession = tws.session
                    this.billNumber = "PGFIN-1"
                    this.billAmount = BigDecimal("42.0000")
                    this.paidAmount = BigDecimal("42.0000")
                    this.startTime = day.atTime(12, 0, 0)
                    this.endTime = day.atTime(12, 0, 0)
                    this.isTraining = false
                    this.zBon = zbon
                }
            PaymentTable.insert {
                it[billId] = bill.id
                it[paymentTypeId] = cash.id
                it[amount] = BigDecimal("42.0000")
                it[paymentStatus] = PaymentStatus.COMPLETED
                it[paymentTime] = day.atTime(12, 0, 0)
                it[currency] = "EUR"
            }
            BillVatDetailTable.insert {
                it[billId] = bill.id
                it[vatId] = vat.id
                it[netAmount] = BigDecimal("35.2941")
                it[vatAmount] = BigDecimal("6.7059")
                it[totalAmount] = BigDecimal("42.0000")
            }
        }
    }

    @AfterAll
    fun stopDatabase() {
        DatabaseFactory.close()
        pg.close()
    }

    @Test
    fun `the grouped query executes and attributes by the Z-Bon day`() {
        val fin =
            transaction {
                repo.getFinancialOverview(windowStart, windowEnd, "EUR", null, false)
            }
        assertEquals("42.00", fin.grossRevenue, "the payment total for the Z-Bon day")
        assertEquals(
            "Cash",
            fin.paymentMethodBreakdown.values
                .single()
                .paymentMethod,
        )
    }
}
