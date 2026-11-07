package org.example.project.features.statistics

import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import org.example.project.core.domain.dto.statistics.StatisticsDto
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
 * A bill list must sort by the same clock the filter selects it with.
 *
 * The filter attributes a bill to its Z-Bon's close, so sorting on the bill's own `endTime` put a
 * bill carried over from the previous evening at the far end of the very list it had just been
 * attributed into — the window said "June" while the ordering said "May".
 *
 * Attributed time alone is not enough either: every bill in one Z-Bon shares that Z-Bon's close
 * timestamp, so it ties a whole trading day. The order-by therefore reads attributed time first,
 * then the bill's own `endTime` to restore real chronology inside the day, then the id as a unique
 * tiebreaker so OFFSET paging cannot duplicate or skip a row.
 *
 * The fixture is built so the two clocks **disagree**: `SORT-EARLY-BILL` is stamped an hour and a
 * half *before* `SORT-LATE-BILL`, yet belongs to a Z-Bon closing a day *later*. Sorting on either
 * clock alone produces a different answer, which is what makes these assertions discriminating.
 */
@DisplayName("Bill list sort order follows Z-Bon attribution")
class StatisticsBrowseSortOrderTest {
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))

    private val windowStart = LocalDate(2026, 5, 1).atTime(0, 0, 0)
    private val windowEnd = LocalDate(2026, 6, 30).atTime(23, 59, 59)

    /**
     * Two Z-Bons closing on consecutive days, and three bills:
     *
     * | bill | own endTime | Z-Bon closes | attributed day |
     * |---|---|---|---|
     * | `SORT-LATE-BILL`  | May 31 23:30 | June 1 | June 1 |
     * | `SORT-SAME-DAY`   | May 31 20:00 | June 1 | June 1 |
     * | `SORT-EARLY-BILL` | May 31 22:00 | June 2 | June 2 |
     *
     * `SORT-EARLY-BILL` is stamped before `SORT-LATE-BILL` but attributes after it. The first two
     * share a Z-Bon, so only their own timestamps can order them.
     */
    private fun seedSortFixture() {
        val admin = TestData.createAdmin()
        val shop = TestData.createShopInfo()
        val tws = TestData.createTableWithSession("SORTATTR")

        val closingJune1 =
            TestData.createZBon(
                shop = shop,
                employee = admin,
                start = LocalDate(2026, 5, 31).atTime(8, 0, 0),
                end = LocalDate(2026, 6, 1).atTime(2, 0, 0),
                nummer = 1L,
            )
        val closingJune2 =
            TestData.createZBon(
                shop = shop,
                employee = admin,
                start = LocalDate(2026, 6, 1).atTime(8, 0, 0),
                end = LocalDate(2026, 6, 2).atTime(2, 0, 0),
                nummer = 2L,
            )

        transaction {
            val session = tws.session

            fun bill(
                number: String,
                endsAt: kotlinx.datetime.LocalDateTime,
                zbon: org.example.project.features.zBon.domain.models.ZBon,
            ) {
                Bill.new {
                    this.employeeId = session.employeeId
                    this.tableSession = session
                    this.billNumber = number
                    this.billAmount = BigDecimal("10.0000")
                    this.paidAmount = BigDecimal("10.0000")
                    this.tips = BigDecimal("0.0000")
                    this.startTime = endsAt
                    this.endTime = endsAt
                    this.isTraining = false
                    this.zBon = zbon
                }
            }

            bill("SORT-LATE-BILL", LocalDate(2026, 5, 31).atTime(23, 30, 0), closingJune1)
            bill("SORT-SAME-DAY", LocalDate(2026, 5, 31).atTime(20, 0, 0), closingJune1)
            bill("SORT-EARLY-BILL", LocalDate(2026, 5, 31).atTime(22, 0, 0), closingJune2)
        }
    }

    private fun billNumbers(sort: StatisticsDto.StatisticsSortOption): List<String> =
        repo
            .getPaginatedBills(windowStart, windowEnd, sort = sort)
            .bills
            .map { it.billNumber }

    @Test
    @DisplayName("RECENT orders by attributed day first, then by the bill's own time within it")
    fun recentFollowsAttribution() =
        testApp(setupData = { seedSortFixture() }) { _ ->
            transaction {
                assertEquals(
                    listOf("SORT-EARLY-BILL", "SORT-LATE-BILL", "SORT-SAME-DAY"),
                    billNumbers(StatisticsDto.StatisticsSortOption.RECENT),
                    "the June-2 Z-Bon's bill must lead despite being stamped 90 minutes earlier; " +
                        "sorting on BillTable.endTime alone would put SORT-LATE-BILL first",
                )
            }
        }

    @Test
    @DisplayName("OLDEST is the exact reverse")
    fun oldestIsTheReverse() =
        testApp(setupData = { seedSortFixture() }) { _ ->
            transaction {
                assertEquals(
                    listOf("SORT-SAME-DAY", "SORT-LATE-BILL", "SORT-EARLY-BILL"),
                    billNumbers(StatisticsDto.StatisticsSortOption.OLDEST),
                    "reversing the sort must reverse the whole list, tiebreakers included",
                )
            }
        }

    @Test
    @DisplayName("bills sharing a Z-Bon keep real chronological order inside the day")
    fun intraDayOrderSurvives() =
        testApp(setupData = { seedSortFixture() }) { _ ->
            transaction {
                // The load-bearing case. SORT-LATE-BILL and SORT-SAME-DAY share a Z-Bon, so their
                // attributed times are identical — attributed time alone cannot order them, and
                // without the endTime term their order would be whatever the database happened to
                // return.
                val recent =
                    billNumbers(StatisticsDto.StatisticsSortOption.RECENT)
                        .filter { it != "SORT-EARLY-BILL" }

                assertEquals(
                    listOf("SORT-LATE-BILL", "SORT-SAME-DAY"),
                    recent,
                    "23:30 must precede 20:00 under RECENT within one Z-Bon",
                )
            }
        }

    @Test
    @DisplayName("amount sorts are unaffected by attribution")
    fun amountSortsIgnoreAttribution() =
        testApp(setupData = { seedSortFixture() }) { _ ->
            transaction {
                // All three bills are 10.00, so an amount sort falls entirely through to the id
                // tiebreaker. The assertion is that it is stable and total, not that it is
                // chronological — amount ordering is independent of the trading day.
                val highest = billNumbers(StatisticsDto.StatisticsSortOption.HIGHEST)
                val lowest = billNumbers(StatisticsDto.StatisticsSortOption.LOWEST)

                assertEquals(3, highest.size)
                assertEquals(highest, lowest.reversed(), "equal amounts must still order deterministically")
            }
        }
}
