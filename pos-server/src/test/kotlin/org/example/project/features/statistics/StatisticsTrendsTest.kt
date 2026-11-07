package org.example.project.features.statistics

import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.statistics.data.repository.StatisticsRepositoryImpl
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Repository-level tests for [StatisticsRepositoryImpl.getSalesTrends].
 *
 * The trends series and the sales-overview headline feed the same screen — a total above a chart
 * of its own parts. They therefore share one bill filter, and these tests pin the invariants a
 * chart silently depends on: all 24 hour buckets present, upper-case weekday keys, chronological
 * days, and a daily series that sums to the overview's total.
 */
@DisplayName("Statistics sales trends")
class StatisticsTrendsTest {
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))

    // Fixed dates, not "today": the weekday assertions below are only meaningful against a known
    // calendar. 2026-05-04 is a Monday, 05-05 a Tuesday, 05-11 the following Monday.
    private val monday = LocalDate(2026, 5, 4)
    private val tuesday = LocalDate(2026, 5, 5)
    private val wednesday = LocalDate(2026, 5, 6)
    private val thursday = LocalDate(2026, 5, 7)
    private val nextMonday = LocalDate(2026, 5, 11)

    private val rangeStart = LocalDate(2026, 5, 1).atTime(0, 0, 0)
    private val rangeEnd = LocalDate(2026, 5, 31).atTime(23, 59, 59)

    // Statistics revenue is attributed by Z-Bon close, not by a bill's own timestamp, so every
    // fixture bill gets a Z-Bon closing on its own date. The one no-Z-Bon case — a bill falling
    // back to its own endTime — is pinned by its own regression test below. Gapless nummer per shop.
    private var zbonSeq = 0L

    /** A single-day Z-Bon closing on [date] (23:59:59), so bills tied to it attribute to [date]. */
    private fun dayZbon(
        shop: ShopInfo,
        admin: Employee,
        date: LocalDate,
    ): ZBon =
        TestData.createZBon(
            shop = shop,
            employee = admin,
            start = date.atTime(0, 0, 0),
            end = date.atTime(23, 59, 59),
            nummer = ++zbonSeq,
        )

    /**
     * Creates one active bill, booked to a Z-Bon closing on [date]. [paid] lands in `paidAmount`,
     * which is exactly what the overview's `totalRevenue` sums, so the two endpoints are being fed
     * from the same column.
     */
    private fun bill(
        session: TableSession,
        shop: ShopInfo,
        admin: Employee,
        number: String,
        date: LocalDate,
        hour: Int,
        paid: String,
    ) {
        val at = date.atTime(hour, 0, 0)
        val zbon = dayZbon(shop, admin, date)
        Bill.new {
            this.employeeId = session.employeeId
            this.tableSession = session
            this.billNumber = number
            this.billAmount = BigDecimal(paid)
            this.paidAmount = BigDecimal(paid)
            this.startTime = at
            this.endTime = at
            this.isTraining = false
            this.zBon = zbon
        }
    }

    @Test
    fun `hourlyRevenue always has all 24 keys even when only two hours saw trade`() =
        testApp(
            setupData = {
                val admin = TestData.createAdmin()
                val shop = TestData.createShopInfo()
                val tws = TestData.createTableWithSession("TRND1")
                transaction {
                    val session = tws.session
                    bill(session, shop, admin, "TRND-H9", monday, 9, "10.0000")
                    bill(session, shop, admin, "TRND-H18", monday, 18, "25.0000")
                }
            },
        ) { _ ->
            transaction {
                val trends = repo.getSalesTrends(rangeStart, rangeEnd, "EUR", null, false)

                // A missing hour makes the chart compress its axis and show a busier day than happened.
                assertEquals(24, trends.hourlyRevenue.size, "every hour of the day must have a bucket")
                assertEquals(
                    (0..23).toList(),
                    trends.hourlyRevenue.keys.toList(),
                    "buckets must be keyed 0..23 in order",
                )
                assertEquals("10.00", trends.hourlyRevenue[9], "09:00 bill")
                assertEquals("25.00", trends.hourlyRevenue[18], "18:00 bill")
                assertEquals("0.00", trends.hourlyRevenue[3], "untraded hour must read zero, not be absent")
                assertEquals("0.00", trends.hourlyRevenue[0])
                assertEquals("0.00", trends.hourlyRevenue[23])

                // The buckets must still add up to the day's revenue.
                val hourlyTotal = trends.hourlyRevenue.values.fold(BigDecimal.ZERO) { acc, v -> acc.add(BigDecimal(v)) }
                assertEquals(BigDecimal("35.00"), hourlyTotal, "hourly buckets must sum to the day's revenue")
            }
        }

    @Test
    fun `weeklyPattern keys are upper-case day names and sum the days that fall on them`() =
        testApp(
            setupData = {
                val admin = TestData.createAdmin()
                val shop = TestData.createShopInfo()
                val tws = TestData.createTableWithSession("TRND2")
                transaction {
                    val session = tws.session
                    bill(session, shop, admin, "TRND-MON1", monday, 12, "10.0000")
                    bill(session, shop, admin, "TRND-MON2", nextMonday, 12, "15.0000")
                    bill(session, shop, admin, "TRND-TUE", tuesday, 12, "7.5000")
                }
            },
        ) { _ ->
            transaction {
                val trends = repo.getSalesTrends(rangeStart, rangeEnd, "EUR", null, false)

                // A casing mismatch renders all-zero bars, which reads as "no trade" rather than a bug.
                assertEquals(setOf("MONDAY", "TUESDAY"), trends.weeklyPattern.keys)
                trends.weeklyPattern.keys.forEach { key ->
                    assertEquals(key.uppercase(), key, "weekday keys must be upper-case DayOfWeek names")
                }

                assertEquals("25.00", trends.weeklyPattern["MONDAY"], "both Mondays summed")
                assertEquals("7.50", trends.weeklyPattern["TUESDAY"])
            }
        }

    @Test
    fun `dailyRevenue matches the overview total and excludes storno, cancelled and training bills`() =
        testApp(
            setupData = {
                val admin = TestData.createAdmin()
                val shop = TestData.createShopInfo()
                val tws = TestData.createTableWithSession("TRND3")
                val trainingTws = TestData.createTableWithSession("TRND4")
                transaction {
                    val session = tws.session
                    val at = monday.atTime(12, 0, 0)

                    bill(session, shop, admin, "TRND-ACTIVE", monday, 12, "22.0000")

                    // The storno/cancelled/training bills each get a Monday-closing Z-Bon too, so they
                    // land INSIDE the window and it is the storno/cancelled/training filters — not the
                    // windowing — that must exclude them, which is exactly what this test pins.
                    val cancelled =
                        Bill.new {
                            this.employeeId = session.employeeId
                            this.tableSession = session
                            this.billNumber = "TRND-CANCELLED"
                            this.billAmount = BigDecimal("10.0000")
                            this.paidAmount = BigDecimal("10.0000")
                            this.startTime = at
                            this.endTime = at
                            this.isTraining = false
                            this.zBon = dayZbon(shop, admin, monday)
                        }
                    Bill.new {
                        this.employeeId = session.employeeId
                        this.tableSession = session
                        this.billNumber = "TRND-STORNO"
                        this.billAmount = BigDecimal("-10.0000")
                        this.paidAmount = BigDecimal("-10.0000")
                        this.startTime = at
                        this.endTime = at
                        this.deletedBill = cancelled
                        this.isTraining = false
                        this.zBon = dayZbon(shop, admin, monday)
                    }
                    cancelled.deletingReason = "wrong order"

                    val trainingSession = trainingTws.session
                    trainingSession.isTraining = true
                    Bill.new {
                        this.employeeId = trainingSession.employeeId
                        this.tableSession = trainingSession
                        this.billNumber = "TRND-TRAINING"
                        this.billAmount = BigDecimal("99.0000")
                        this.paidAmount = BigDecimal("99.0000")
                        this.startTime = at
                        this.endTime = at
                        this.isTraining = true
                        this.zBon = dayZbon(shop, admin, monday)
                    }
                }
            },
        ) { _ ->
            transaction {
                val trends = repo.getSalesTrends(rangeStart, rangeEnd, "EUR", null, false)
                val overview = repo.getSalesOverviewSummary(rangeStart, rangeEnd, "EUR", null, false)

                val dailyTotal = trends.dailyRevenue.values.fold(BigDecimal.ZERO) { acc, v -> acc.add(BigDecimal(v)) }
                assertEquals(
                    BigDecimal(overview.totalRevenue),
                    dailyTotal,
                    "the trend series must sum to the overview headline it sits under",
                )
                assertEquals("22.00", trends.dailyRevenue[monday.toString()], "storno/cancelled/training excluded")
                assertEquals(1, trends.dailyTransactionCounts[monday.toString()])
                assertEquals(
                    overview.totalBillCount,
                    trends.dailyTransactionCounts.values.sum(),
                    "counts must match the overview's bill count",
                )

                val hourlyTotal = trends.hourlyRevenue.values.fold(BigDecimal.ZERO) { acc, v -> acc.add(BigDecimal(v)) }
                assertEquals(BigDecimal(overview.totalRevenue), hourlyTotal, "hourly series must agree too")
            }
        }

    @Test
    fun `dailyRevenue is chronological and growthRate compares the second half to the first`() =
        testApp(
            setupData = {
                val admin = TestData.createAdmin()
                val shop = TestData.createShopInfo()
                val tws = TestData.createTableWithSession("TRND5")
                transaction {
                    val session = tws.session
                    // Deliberately inserted out of order: the ordering must come from the query, not
                    // from the order rows happen to be written in.
                    bill(session, shop, admin, "TRND-D3", wednesday, 12, "15.0000")
                    bill(session, shop, admin, "TRND-D1", monday, 12, "10.0000")
                    bill(session, shop, admin, "TRND-D4", thursday, 12, "15.0000")
                    bill(session, shop, admin, "TRND-D2", tuesday, 12, "10.0000")
                }
            },
        ) { _ ->
            transaction {
                val trends = repo.getSalesTrends(rangeStart, rangeEnd, "EUR", null, false)

                assertEquals(
                    listOf(monday, tuesday, wednesday, thursday).map { it.toString() },
                    trends.dailyRevenue.keys.toList(),
                    "growthRate's first-half/second-half split is meaningless unless days are chronological",
                )
                assertEquals(
                    trends.dailyRevenue.keys.toList(),
                    trends.dailyRevenue.keys.sorted(),
                    "ISO day keys must be ascending",
                )

                // First half 10 + 10 = 20, second half 15 + 15 = 30 -> +50 %.
                assertEquals("50.00", trends.growthRate)

                // Ties broken by insertion order, so only assert membership of the top pair.
                assertEquals(3, trends.bestPerformingDays.size)
                assertTrue(
                    trends.bestPerformingDays.take(2).toSet() ==
                        setOf(wednesday.toString(), thursday.toString()),
                    "the two 15.00 days must lead bestPerformingDays, got ${trends.bestPerformingDays}",
                )
            }
        }

    @Test
    fun `an empty window yields zeroed hours and a neutral growth rate`() =
        testApp(
            setupData = { TestData.createAdmin() },
        ) { _ ->
            transaction {
                val trends = repo.getSalesTrends(rangeStart, rangeEnd, "EUR", null, false)

                assertEquals(24, trends.hourlyRevenue.size, "hour buckets exist even with no trade at all")
                assertTrue(trends.hourlyRevenue.values.all { it == "0.00" })
                assertTrue(trends.dailyRevenue.isEmpty())
                assertTrue(trends.weeklyPattern.isEmpty())
                assertTrue(trends.bestPerformingDays.isEmpty())
                // A single-point or empty series has no growth to report; it must never render as 100 %.
                assertEquals("0.00", trends.growthRate)
            }
        }

    @Test
    fun `a bill with no Z-Bon is booked to its own endTime and still matches the overview`() =
        testApp(
            setupData = {
                val tws = TestData.createTableWithSession("TRND6")
                transaction {
                    val session = tws.session
                    // No `this.zBon`: the bill is unassigned and falls back to its OWN endTime
                    // (COALESCE(ZBon.endTime, Bill.endTime)), landing on Monday without needing a
                    // closing Z-Bon. Regression: the previous fallback was "now", which put this
                    // bill outside the fixed May window and blanked the series.
                    val at = monday.atTime(12, 0, 0)
                    Bill.new {
                        this.employeeId = session.employeeId
                        this.tableSession = session
                        this.billNumber = "TRND-OPEN"
                        this.billAmount = BigDecimal("30.0000")
                        this.paidAmount = BigDecimal("30.0000")
                        this.startTime = at
                        this.endTime = at
                        this.isTraining = false
                    }
                }
            },
        ) { _ ->
            transaction {
                val trends = repo.getSalesTrends(rangeStart, rangeEnd, "EUR", null, false)
                val overview = repo.getSalesOverviewSummary(rangeStart, rangeEnd, "EUR", null, false)

                // Daily series: the unassigned bill lands on the day of its own endTime.
                assertEquals(
                    "30.00",
                    trends.dailyRevenue[monday.toString()],
                    "an unassigned bill must attribute to its own endTime day",
                )
                assertEquals(1, trends.dailyTransactionCounts[monday.toString()])

                // Hourly series: the bucket is keyed by the bill's clock time, like any other bill.
                assertEquals("30.00", trends.hourlyRevenue[12], "the 12:00 bill lands in hour 12")

                // The chart must sum to the headline it sits under.
                val dailyTotal = trends.dailyRevenue.values.fold(BigDecimal.ZERO) { acc, v -> acc.add(BigDecimal(v)) }
                assertEquals(BigDecimal("30.00"), dailyTotal, "the daily series must sum to the day's revenue")
                assertEquals(BigDecimal(overview.totalRevenue), dailyTotal, "and to the overview headline")
                assertEquals("30.00", overview.totalRevenue)
            }
        }
}
