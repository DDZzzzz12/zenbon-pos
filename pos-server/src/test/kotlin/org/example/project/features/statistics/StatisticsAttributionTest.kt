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
 * Pins that statistics revenue is attributed to the Z-Bon that booked it, not to each bill's own
 * timestamp.
 *
 * A German restaurant cashes up once a day into a Z-Bon (fiscal daily close). A Z-Bon may span a
 * calendar boundary: a session that opened at 23:00 and closed the next day is fiscally the second
 * day's business, including its late-evening clock time of the first day. The overview must agree
 * with that close, not with the wall clock a bill was rung at. A bill not yet booked into a Z-Bon
 * falls back to its own `BillTable.endTime` — never to the wall clock of "now" — so unassigned
 * bills land in whatever window contains their endTime and vanish from every other window.
 */
@DisplayName("Statistics Z-Bon attribution")
class StatisticsAttributionTest {
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))

    // A Z-Bon whose window straddles midnight: opened 2026-05-31 23:00, closed 2026-06-01 22:00.
    // Its close is on June 1, so every bill it booked is June 1's business — the 23:30 bill included.
    private val zbonStart = LocalDate(2026, 5, 31).atTime(23, 0, 0)
    private val zbonEnd = LocalDate(2026, 6, 1).atTime(22, 0, 0)

    private val mayStart = LocalDate(2026, 5, 1).atTime(0, 0, 0)
    private val mayEnd = LocalDate(2026, 5, 31).atTime(23, 59, 59)
    private val juneStart = LocalDate(2026, 6, 1).atTime(0, 0, 0)
    private val juneEnd = LocalDate(2026, 6, 30).atTime(23, 59, 59)

    @Test
    fun `a bill rung before midnight is booked to its Z-Bon's day, not its own`() =
        testApp(
            setupData = {
                val admin = TestData.createAdmin()
                val shop = TestData.createShopInfo()
                val tws = TestData.createTableWithSession("ATTR1")
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
                    Bill.new {
                        this.employeeId = session.employeeId
                        this.tableSession = session
                        this.billNumber = "ATTR-LATE"
                        this.billAmount = BigDecimal("10.0000")
                        this.paidAmount = BigDecimal("10.0000")
                        this.startTime = LocalDate(2026, 5, 31).atTime(23, 30, 0)
                        this.endTime = LocalDate(2026, 5, 31).atTime(23, 30, 0)
                        this.isTraining = false
                        this.zBon = zbon
                    }

                    // Rung 2026-06-01 12:00 — squarely inside the same Z-Bon.
                    Bill.new {
                        this.employeeId = session.employeeId
                        this.tableSession = session
                        this.billNumber = "ATTR-NOON"
                        this.billAmount = BigDecimal("10.0000")
                        this.paidAmount = BigDecimal("10.0000")
                        this.startTime = LocalDate(2026, 6, 1).atTime(12, 0, 0)
                        this.endTime = LocalDate(2026, 6, 1).atTime(12, 0, 0)
                        this.isTraining = false
                        this.zBon = zbon
                    }
                }
            },
        ) { _ ->
            transaction {
                // May must NOT claim the 23:30 bill: its Z-Bon closed in June.
                val may = repo.getSalesOverviewSummary(mayStart, mayEnd, "EUR", null, false)
                assertEquals(
                    "0.00",
                    may.totalRevenue,
                    "May must not claim a bill whose Z-Bon closed on June 1",
                )

                // June owns the whole Z-Bon — both the noon bill and the 23:30 bill from May 31.
                val june = repo.getSalesOverviewSummary(juneStart, juneEnd, "EUR", null, false)
                assertEquals(
                    "20.00",
                    june.totalRevenue,
                    "June owns the whole Z-Bon, including its May 31 clock time",
                )
            }
        }

    // emu21 NOTE-m: "since the last Z-Bon" asks from the previous closing's end. A closed bill is
    // attributed to that very instant, so an inclusive start counted the whole previous closing again
    // (63 bills instead of 15). The start is exclusive, as the Z-Bon defines its own period.
    @Test
    fun `a window starting exactly at the previous closing's end does not count that closing`() =
        testApp(
            setupData = {
                val admin = TestData.createAdmin()
                val shop = TestData.createShopInfo()
                val tws = TestData.createTableWithSession("ATTR-EXCL")
                val zbon = TestData.createZBon(shop = shop, employee = admin, start = zbonStart, end = zbonEnd, nummer = 1L)
                transaction {
                    val session = tws.session
                    Bill.new {
                        this.employeeId = session.employeeId
                        this.tableSession = session
                        this.billNumber = "ATTR-CLOSED"
                        this.billAmount = BigDecimal("40.0000")
                        this.paidAmount = BigDecimal("40.0000")
                        this.startTime = LocalDate(2026, 6, 1).atTime(12, 0, 0)
                        this.endTime = LocalDate(2026, 6, 1).atTime(12, 0, 0)
                        this.isTraining = false
                        this.zBon = zbon
                    }
                    // After the closing, not yet in a Z-Bon: the open period's only sale.
                    Bill.new {
                        this.employeeId = session.employeeId
                        this.tableSession = session
                        this.billNumber = "ATTR-AFTER"
                        this.billAmount = BigDecimal("7.5000")
                        this.paidAmount = BigDecimal("7.5000")
                        this.startTime = LocalDate(2026, 6, 1).atTime(22, 30, 0)
                        this.endTime = LocalDate(2026, 6, 1).atTime(22, 30, 0)
                        this.isTraining = false
                    }
                }
            },
        ) { _ ->
            transaction {
                val sinceClosing = repo.getSalesOverviewSummary(zbonEnd, juneEnd, "EUR", null, false)
                assertEquals("7.50", sinceClosing.totalRevenue, "only the sale after the closing")
                assertEquals(1, sinceClosing.totalBillCount, "the closing's bill is not counted again")

                // The closing's own window still owns its bill: its end is inclusive.
                val closing = repo.getSalesOverviewSummary(zbonStart, zbonEnd, "EUR", null, false)
                assertEquals("40.00", closing.totalRevenue)
            }
        }

    @Test
    fun `a bill with no Z-Bon is booked to its own endTime, not to now`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                val tws = TestData.createTableWithSession("ATTR-OPEN")
                transaction {
                    val session = tws.session
                    // No `this.zBon`: the bill stays open (zBonId == null), the live current session.
                    // Without a Z-Bon the attribution falls back to the bill's OWN endTime
                    // (COALESCE(ZBon.endTime, Bill.endTime)), so a June bill is June's business...
                    Bill.new {
                        this.employeeId = session.employeeId
                        this.tableSession = session
                        this.billNumber = "ATTR-OPEN-1"
                        this.billAmount = BigDecimal("15.0000")
                        this.paidAmount = BigDecimal("15.0000")
                        this.startTime = LocalDate(2026, 6, 1).atTime(12, 0, 0)
                        this.endTime = LocalDate(2026, 6, 1).atTime(12, 0, 0)
                        this.isTraining = false
                    }

                    // ...and a bill closed years ago is old business, not "today".
                    Bill.new {
                        this.employeeId = session.employeeId
                        this.tableSession = session
                        this.billNumber = "ATTR-OLD-1"
                        this.billAmount = BigDecimal("8.0000")
                        this.paidAmount = BigDecimal("8.0000")
                        this.startTime = LocalDate(2020, 1, 15).atTime(12, 0, 0)
                        this.endTime = LocalDate(2020, 1, 15).atTime(12, 0, 0)
                        this.isTraining = false
                    }
                }
            },
        ) { _ ->
            transaction {
                // June claims the unassigned June bill: its own endTime falls inside the window.
                val june = repo.getSalesOverviewSummary(juneStart, juneEnd, "EUR", null, false)
                assertEquals(
                    "15.00",
                    june.totalRevenue,
                    "an unassigned bill is booked to its own endTime, so a window containing it must count it",
                )

                // 2020 claims the old bill by the same rule: endTime, not the wall clock of "now".
                val oldWindow =
                    repo.getSalesOverviewSummary(
                        LocalDate(2020, 1, 1).atTime(0, 0, 0),
                        LocalDate(2020, 12, 31).atTime(23, 59, 59),
                        "EUR",
                        null,
                        false,
                    )
                assertEquals(
                    "8.00",
                    oldWindow.totalRevenue,
                    "an unassigned bill attributes to its own endTime even when that endTime is long past",
                )

                // A window containing neither endTime claims nothing.
                val unrelated =
                    repo.getSalesOverviewSummary(
                        LocalDate(2019, 1, 1).atTime(0, 0, 0),
                        LocalDate(2019, 1, 31).atTime(23, 59, 59),
                        "EUR",
                        null,
                        false,
                    )
                assertEquals(
                    "0.00",
                    unrelated.totalRevenue,
                    "an unrelated past window must not claim either bill",
                )
            }
        }
}
