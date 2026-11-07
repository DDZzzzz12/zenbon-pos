package org.example.project.features.statistics

import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.statistics.data.repository.StatisticsRepositoryImpl
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Pins that the four browse/list endpoints — `getPaginatedBills`, `getPaginatedTableCards`,
 * `getSessionSummariesForTable` and `getPaginatedEmployeeGroups` — attribute a bill by the day its
 * Z-Bon closed rather than by the bill's own wall-clock timestamp, the same fiscal convention
 * `getSalesOverviewSummary` and the catalog aggregates already follow.
 *
 * A German restaurant cashes up once a day into a Z-Bon (fiscal daily close), and a Z-Bon may
 * straddle a calendar boundary: a bill rung at 23:30 on May 31 whose Z-Bon closes on June 1 is
 * fiscally June 1's business. Without this the bill list, the table cards and the employee groups
 * disagreed with the financial overview for the same window.
 *
 * Three things are load-bearing here and each has its own assertion:
 *  - the May window must NOT claim a bill whose Z-Bon closed in June;
 *  - the June window must claim it, with the exact amounts — the Z-Bon join is many-to-one on
 *    ZBonTable's primary key, so no total may double;
 *  - a bill not yet booked into a Z-Bon (`zBonId == null`) must still appear, attributed to its own
 *    endTime. That is the COALESCE fallback, and it is what a LEFT JOIN silently turned INNER would
 *    destroy — in a live POS those rows are *today's* trading.
 */
@DisplayName("Browse/list Z-Bon attribution")
class StatisticsBrowseAttributionTest {
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))

    // A Z-Bon whose window straddles midnight: opened 2026-05-31 23:00, closed 2026-06-01 22:00.
    private val zbonStart = LocalDate(2026, 5, 31).atTime(23, 0, 0)
    private val zbonEnd = LocalDate(2026, 6, 1).atTime(22, 0, 0)

    private val mayStart = LocalDate(2026, 5, 1).atTime(0, 0, 0)
    private val mayEnd = LocalDate(2026, 5, 31).atTime(23, 59, 59)
    private val juneStart = LocalDate(2026, 6, 1).atTime(0, 0, 0)
    private val juneEnd = LocalDate(2026, 6, 30).atTime(23, 59, 59)

    // Wide enough to hold both bills at once — the window where a duplicating join would be most
    // visible, since one bill has a Z-Bon row to duplicate against and the other does not.
    private val allStart = LocalDate(2020, 1, 1).atTime(0, 0, 0)
    private val allEnd = LocalDate(2100, 1, 1).atTime(0, 0, 0)

    /** Set by [seedBrowseFixture]; `getSessionSummariesForTable` is addressed by table id. */
    private var tableId: Long = 0

    /**
     * Seeds one table with one session carrying two bills:
     *  - `BRW-LATE` — rung 2026-05-31 23:30, 24.00 sold + 3.00 tip = 27.00 tendered, attached to a
     *    Z-Bon closing June 1. Fiscally June's business despite its May timestamp.
     *  - `BRW-OPEN` — rung 2026-05-15 12:00, 10.00 sold + 1.00 tip = 11.00 tendered, no Z-Bon yet.
     *    Falls back to its own endTime, so it is May's business.
     *
     * `paidAmount` is the TENDERED total, so it carries the tip. These two bills used to seed
     * `paidAmount` WITHOUT it (24.00 next to tips of 3.00) — a shape `BillServiceImpl` cannot
     * produce, since it sets `paidAmount = payments.sumOf { it.amount }`. Correcting it leaves every
     * revenue assertion below unchanged, because an employee tip is not turnover (plan 2026-09-16
     * P1.4); only `getPaginatedBills.totalAmount` moves, and that one is a takings figure which
     * keeps the tip by design.
     *
     * Both sit on the same session, table and employee, so every endpoint under test has to split
     * them by attribution alone rather than by grouping key.
     *
     * A second, closed Z-Bon that no bill references is seeded on purpose: a correct many-to-one
     * join on ZBonTable's primary key ignores it, whereas a cartesian or mis-keyed join would
     * multiply the June rows by it and the amount assertions would catch that.
     */
    private fun seedBrowseFixture() {
        val admin = TestData.createAdmin()
        val shop = TestData.createShopInfo()
        val tws = TestData.createTableWithSession("BRWATTR")
        val zbon = TestData.createZBon(shop, admin, zbonStart, zbonEnd, nummer = 1L)
        TestData.createZBon(
            shop = shop,
            employee = admin,
            start = LocalDate(2026, 6, 2).atTime(8, 0, 0),
            end = LocalDate(2026, 6, 2).atTime(22, 0, 0),
            nummer = 2L,
        )
        tableId = tws.table.id.value

        transaction {
            val session = tws.session

            Bill.new {
                this.employeeId = session.employeeId
                this.tableSession = session
                this.billNumber = "BRW-LATE"
                this.billAmount = BigDecimal("24.0000")
                this.paidAmount = BigDecimal("27.0000")
                this.tips = BigDecimal("3.0000")
                this.startTime = LocalDate(2026, 5, 31).atTime(23, 30, 0)
                this.endTime = LocalDate(2026, 5, 31).atTime(23, 30, 0)
                this.isTraining = false
                this.zBon = zbon
            }

            Bill.new {
                this.employeeId = session.employeeId
                this.tableSession = session
                this.billNumber = "BRW-OPEN"
                this.billAmount = BigDecimal("10.0000")
                this.paidAmount = BigDecimal("11.0000")
                this.tips = BigDecimal("1.0000")
                this.startTime = LocalDate(2026, 5, 15).atTime(12, 0, 0)
                this.endTime = LocalDate(2026, 5, 15).atTime(12, 0, 0)
                this.isTraining = false
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
    fun `getPaginatedBills books the late bill to its Z-Bon day and keeps the un-cashed-up bill on its own`() =
        testApp(setupData = { seedBrowseFixture() }) { _ ->
            transaction {
                // May holds only the bill that has no Z-Bon yet — the COALESCE fallback. If the join
                // were INNER this list would be empty and a live POS would lose today's trading.
                val may = repo.getPaginatedBills(mayStart, mayEnd)
                assertEquals(
                    listOf("BRW-OPEN"),
                    may.bills.map { it.billNumber },
                    "May keeps the un-cashed-up bill and must not claim one whose Z-Bon closed in June",
                )
                assertEquals(1L, may.pagination.totalItems, "count and page must agree")
                assertMoney("11.00", may.totalAmount, "May cross-page takings — 10.00 sold + 1.00 tip")
                assertMoney("1.00", may.totalTips, "May cross-page tips")

                // June owns the late bill, once — not twice.
                val june = repo.getPaginatedBills(juneStart, juneEnd)
                assertEquals(
                    listOf("BRW-LATE"),
                    june.bills.map { it.billNumber },
                    "June owns the bill its Z-Bon closed",
                )
                assertEquals(1L, june.pagination.totalItems, "the Z-Bon join must not duplicate the row")
                assertMoney("27.00", june.totalAmount, "paidAmount must not double across the Z-Bon join")
                assertMoney("3.00", june.totalTips, "tips must not double across the Z-Bon join")
            }
        }

    @Test
    fun `getPaginatedTableCards books the late bill to its Z-Bon day and keeps the un-cashed-up bill on its own`() =
        testApp(setupData = { seedBrowseFixture() }) { _ ->
            transaction {
                val may = repo.getPaginatedTableCards(mayStart, mayEnd)
                val mayCard = may.tableCards.single()
                assertEquals("BRWATTR", mayCard.tableNumber)
                assertEquals(1, mayCard.totalBillCount, "only the un-cashed-up bill is May's")
                assertMoney("10.00", mayCard.totalRevenue, "May revenue")
                assertMoney("1.00", mayCard.totalTips, "May tips")
                assertEquals(1L, may.pagination.totalItems, "one distinct table")

                val june = repo.getPaginatedTableCards(juneStart, juneEnd)
                val juneCard = june.tableCards.single()
                assertEquals(
                    1,
                    juneCard.totalBillCount,
                    "bill count is a plain count, so a duplicating Z-Bon join would show 2",
                )
                assertEquals(1, juneCard.sessionCount, "one session")
                assertMoney("24.00", juneCard.totalRevenue, "revenue must not double across the Z-Bon join")
                assertMoney("3.00", juneCard.totalTips, "tips must not double across the Z-Bon join")
                assertEquals(1L, june.pagination.totalItems, "countDistinct over one table")
            }
        }

    @Test
    fun `getSessionSummariesForTable books the late bill to its Z-Bon day and keeps the un-cashed-up bill on its own`() =
        testApp(setupData = { seedBrowseFixture() }) { _ ->
            transaction {
                val may = repo.getSessionSummariesForTable(tableId, mayStart, mayEnd).single()
                assertEquals(1, may.billCount, "only the un-cashed-up bill is May's")
                assertMoney("10.00", may.revenue, "May revenue")
                assertMoney("1.00", may.tips, "May tips")
                assertEquals(
                    listOf("BRW-OPEN"),
                    may.bills.map { it.billNumber },
                    "the per-session bill list runs off the same clause and must agree with the aggregate",
                )

                val june = repo.getSessionSummariesForTable(tableId, juneStart, juneEnd).single()
                assertEquals(1, june.billCount, "a duplicating Z-Bon join would show 2")
                assertMoney("24.00", june.revenue, "revenue must not double across the Z-Bon join")
                assertMoney("3.00", june.tips, "tips must not double across the Z-Bon join")
                assertEquals(
                    listOf("BRW-LATE"),
                    june.bills.map { it.billNumber },
                    "the per-session bill list must be attributed too, not left on the bill's own endTime",
                )
            }
        }

    @Test
    fun `getPaginatedEmployeeGroups books the late bill to its Z-Bon day and keeps the un-cashed-up bill on its own`() =
        testApp(setupData = { seedBrowseFixture() }) { _ ->
            transaction {
                val may = repo.getPaginatedEmployeeGroups(mayStart, mayEnd)
                val mayGroup = may.employeeGroups.single()
                assertEquals(1, mayGroup.billCount, "only the un-cashed-up bill is May's")
                assertMoney("10.00", mayGroup.totalRevenue, "May revenue")
                assertMoney("1.00", mayGroup.totalTips, "May tips")
                assertEquals(
                    listOf("BRW-OPEN"),
                    mayGroup.bills.map { it.billNumber },
                    "the per-employee bill list runs off the same clause and must agree with the aggregate",
                )
                assertEquals(1L, may.pagination.totalItems, "one distinct employee")

                val june = repo.getPaginatedEmployeeGroups(juneStart, juneEnd)
                val juneGroup = june.employeeGroups.single()
                assertEquals(1, juneGroup.billCount, "a duplicating Z-Bon join would show 2")
                assertMoney("24.00", juneGroup.totalRevenue, "revenue must not double across the Z-Bon join")
                assertMoney("3.00", juneGroup.totalTips, "tips must not double across the Z-Bon join")
                assertEquals(
                    listOf("BRW-LATE"),
                    juneGroup.bills.map { it.billNumber },
                    "the per-employee bill list must be attributed too",
                )
                assertEquals(1L, june.pagination.totalItems, "countDistinct over one employee")
            }
        }

    /**
     * The window that holds both bills at once. Every endpoint must report each bill exactly once —
     * 34.00 of revenue and 4.00 of tips, or 38.00 of takings where the figure is `totalAmount` —
     * the case where a Z-Bon join that multiplied rows would be unmistakable,
     * since one of the two bills has a Z-Bon row to multiply against and the other does not.
     */
    @Test
    fun `a window holding both bills counts each exactly once on all four endpoints`() =
        testApp(setupData = { seedBrowseFixture() }) { _ ->
            transaction {
                val bills = repo.getPaginatedBills(allStart, allEnd)
                assertEquals(
                    setOf("BRW-LATE", "BRW-OPEN"),
                    bills.bills.map { it.billNumber }.toSet(),
                    "both bills, each once",
                )
                assertEquals(2, bills.bills.size, "no duplicate rows on the page")
                assertEquals(2L, bills.pagination.totalItems)
                assertMoney("38.00", bills.totalAmount, "takings: 27.00 + 11.00, not 76.00")
                assertMoney("4.00", bills.totalTips, "3.00 + 1.00")

                val card = repo.getPaginatedTableCards(allStart, allEnd).tableCards.single()
                assertEquals(2, card.totalBillCount)
                assertEquals(1, card.sessionCount)
                assertMoney("34.00", card.totalRevenue, "24.00 + 10.00, not 58.00")
                assertMoney("4.00", card.totalTips, "3.00 + 1.00")

                val session = repo.getSessionSummariesForTable(tableId, allStart, allEnd).single()
                assertEquals(2, session.billCount)
                assertMoney("34.00", session.revenue, "24.00 + 10.00, not 58.00")
                assertMoney("4.00", session.tips, "3.00 + 1.00")
                assertEquals(2, session.bills.size, "no duplicate rows in the per-session bill list")

                val group = repo.getPaginatedEmployeeGroups(allStart, allEnd).employeeGroups.single()
                assertEquals(2, group.billCount)
                assertMoney("34.00", group.totalRevenue, "24.00 + 10.00, not 58.00")
                assertMoney("4.00", group.totalTips, "3.00 + 1.00")
                assertEquals(2, group.bills.size, "no duplicate rows in the per-employee bill list")
            }
        }
}
