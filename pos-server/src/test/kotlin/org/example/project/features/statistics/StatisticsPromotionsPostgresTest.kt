package org.example.project.features.statistics

import com.zaxxer.hikari.HikariConfig
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.database.DatabaseFactory
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.example.project.features.promotion.database.tables.PromotionTable
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
 * Runs the promotions grouped query (`getPromotionBreakdown`) against a real PostgreSQL (zonky
 * embedded PG), not H2. The grouping uses `COALESCE(zbon.end_time, now)` — Postgres enforces the
 * grouped SELECT expression to match the GROUP BY expression exactly, so a bound-parameter fallback
 * (a fresh `$N` per occurrence) would throw "must appear in the GROUP BY clause". H2 tolerates it.
 * The assertion that matters is that the query *executes*.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Promotions grouped query runs on real PostgreSQL")
class StatisticsPromotionsPostgresTest {
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
        val tws = TestData.createTableWithSession("PGPRO")
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
                    this.billNumber = "PGPRO-1"
                    this.billAmount = BigDecimal("20.0000")
                    this.paidAmount = BigDecimal("20.0000")
                    this.startTime = day.atTime(12, 0, 0)
                    this.endTime = day.atTime(12, 0, 0)
                    this.isTraining = false
                    this.zBon = zbon
                }
            val happyHourId =
                PromotionTable.insert {
                    it[name] = "Happy Hour -20%"
                    it[discountType] = DiscountType.PERCENTAGE
                    it[discountValue] = BigDecimal("20.0000")
                    it[scope] = PromotionScope.BILL_WIDE
                    it[validFrom] = day.atTime(0, 0, 0)
                    it[createdBy] = admin.id
                    it[createdAt] = day.atTime(0, 0, 0)
                } get PromotionTable.id
            BillPromotionTable.insert {
                it[billId] = bill.id
                it[promotionId] = happyHourId
                it[deductedAmount] = BigDecimal("4.0000")
                it[appliedBy] = admin.id
                it[appliedAt] = day.atTime(12, 0, 0)
            }
            BillPromotionTable.insert {
                it[billId] = bill.id
                it[promotionId] = happyHourId
                it[deductedAmount] = BigDecimal("4.0000")
                it[appliedBy] = admin.id
                it[appliedAt] = day.atTime(12, 0, 0)
            }
        }
    }

    @AfterAll
    fun stopDatabase() {
        DatabaseFactory.close()
        pg.close()
    }

    @Test
    fun `getPromotionBreakdown grouped query executes and attributes by the Z-Bon day`() {
        val promotions =
            transaction {
                repo.getPromotionBreakdown(windowStart, windowEnd, "EUR", trainingOnly = false)
            }
        val happyHour = promotions.single { it.name == "Happy Hour -20%" }
        assertEquals(2L, happyHour.timesApplied, "two bill_promotion rows in the Z-Bon window")
        // SUM(DECIMAL(19,4)) keeps scale 4 on Postgres ("8.0000") vs 2 on H2 ("8.00") — compare
        // numerically, exactly like the H2 attribution test's assertMoney.
        assertEquals(
            0,
            BigDecimal("8.00").compareTo(BigDecimal(happyHour.totalDeducted)),
            "Σ 4.00 + 4.00",
        )
    }
}
