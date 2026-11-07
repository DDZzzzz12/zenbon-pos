package org.example.project.features.statistics

import com.zaxxer.hikari.HikariConfig
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import org.example.project.database.DatabaseFactory
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.statistics.data.repository.StatisticsRepositoryImpl
import org.example.project.infrastructure.plugins.initDB
import org.example.project.testutils.PostgresTestDatabase
import org.example.project.testutils.TestData
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
 * Runs `getSalesTrends` against a real PostgreSQL (zonky embedded PG), not H2.
 *
 * The Z-Bon attribution groups by `COALESCE(zbon.end_time, bill.end_time).date()`. Both branches
 * are plain table columns, so the SELECT and GROUP BY render identically by construction — the
 * bound-parameter mismatch that once made Postgres throw "end_time must appear in the GROUP BY
 * clause" (while H2 stayed green) is structurally gone. This test keeps exercising the real dialect
 * so a regression there still fails loudly. Run with `-PincludePostgres` / the `postgres` tag.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("StatisticsRepositoryImpl.getSalesTrends runs on real PostgreSQL")
class StatisticsTrendsPostgresTest {
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
        val tws = TestData.createTableWithSession("PGTRND")
        // A Z-Bon closing on 10 June, over one bill rung that day.
        val zbon =
            TestData.createZBon(
                shop = shop,
                employee = admin,
                start = day.atTime(0, 0, 0),
                end = day.atTime(23, 59, 59),
                nummer = 1L,
            )
        transaction {
            Bill.new {
                this.employeeId = tws.session.employeeId
                this.tableSession = tws.session
                this.billNumber = "PGTRND-1"
                this.billAmount = BigDecimal("42.0000")
                this.paidAmount = BigDecimal("42.0000")
                this.startTime = day.atTime(12, 0, 0)
                this.endTime = day.atTime(12, 0, 0)
                this.isTraining = false
                this.zBon = zbon
            }
        }
    }

    @AfterAll
    fun stopDatabase() {
        DatabaseFactory.close()
        pg.close()
    }

    @Test
    fun `the grouped daily query executes and attributes by the Z-Bon day`() {
        // The bug was a Postgres GROUP BY rejection, so the load-bearing assertion is simply that
        // this does not throw. The value assertions confirm attribution still works on Postgres.
        val trends =
            transaction {
                repo.getSalesTrends(windowStart, windowEnd, "EUR", null, false)
            }
        assertEquals("42.00", trends.dailyRevenue["2026-06-10"], "the bill's day carries its revenue")
        assertEquals(24, trends.hourlyRevenue.size, "all 24 hour buckets present")
        assertEquals("42.00", trends.hourlyRevenue[12], "the 12:00 bill lands in hour 12 by clock time")
    }
}
