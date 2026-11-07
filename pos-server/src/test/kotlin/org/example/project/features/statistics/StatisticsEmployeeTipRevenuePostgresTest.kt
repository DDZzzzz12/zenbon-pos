package org.example.project.features.statistics

import com.zaxxer.hikari.HikariConfig
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atTime
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.database.DatabaseFactory
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.statistics.data.repository.StatisticsRepositoryImpl
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.infrastructure.plugins.initDB
import org.example.project.testutils.PostgresTestDatabase
import org.example.project.testutils.TestData
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
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
 * Runs the six revenue queries that subtract employee tips against a real PostgreSQL (zonky
 * embedded PG), not H2.
 *
 * ### Why this exists
 *
 * `StatisticsRepositoryImpl.employeeTipSumExpr()` — the E2E-2026-09-15 N1 fix that stops an EMPLOYEE
 * tip (TrinkgeldAN) counting as turnover — renders as
 *
 * ```sql
 * SUM(CASE WHEN bill.tip_recipient = ? THEN bill.tips ELSE 0.0000 END)
 * ```
 *
 * a `DECIMAL(19,4)` column in the THEN arm against a literal in the ELSE arm, wrapped in `SUM()`.
 * Postgres types `CASE` branches strictly where H2 is lenient, so a type or scale mismatch there is
 * a **runtime failure on a live statistics endpoint** that no H2 test can see. The sibling
 * `StatisticsRevenueExcludesEmployeeTipTest` proves the *arithmetic* — but only on H2, and no other
 * `@Tag("postgres")` statistics suite reaches these expressions with a TIPPED bill. This suite
 * closes that engine gap: what matters most is that each of the six queries *executes* on the real
 * dialect, with the values asserted so a silently-wrong CASE cannot pass either.
 *
 * ### The two `getFinancialOverview` queries joined as follow-up F16
 *
 * Follow-up F5 took the employee tip out of `getFinancialOverview` too — the headline `grossRevenue`
 * and the per-day `dailyFinancialSummary` entry — through a fifth `employeeTipSumExpr()` call site
 * that feeds TWO statements: a flat aggregate and a `GROUP BY` one. Both were H2-only.
 *
 * `StatisticsFinancialPostgresTest` does run `getFinancialOverview` on the real dialect, so a CASE
 * that Postgres refuses to *type* would already fail there — but its bill carries no tip, so that
 * suite pins none of what the expression is FOR: the arithmetic, the per-day bucketing, or the
 * payment fan-out. The three tests below are those, on Postgres:
 *
 *  - the June window (the shared two-bill fixture) for the headline and its single day, in both
 *    directions — the employee tip out, the owner tip in;
 *  - a July window holding ONE bill settled by TWO payments. `baseQuery` fans out to one row per
 *    payment, so this is the trap the F5 fix had to avoid: the tip aggregate runs over bare
 *    `BillTable` precisely so a bill-level tip is not subtracted once per payment row. Pre-fix that
 *    day reads 26.90 and the naive fan-out version reads 11.70; only 19.30 passes;
 *  - a June-to-July window, where the grouped CASE has to produce two day buckets that sum to the
 *    headline — `MonthlyCloseServiceImpl` renders that series as the Bruttoumsatz KPI's sparkline,
 *    so a bucket that disagrees with the headline is a visible defect.
 *
 * ### The fixture is hand-seeded, deliberately, and this is why
 *
 * The sibling H2 test closes its bills through the real [org.example.project.features.bill.domain.service.BillService],
 * which is the right way to build a tipped bill: the closer's role is what derives the recipient
 * (`BillRepositoryImpl`: non-ADMIN → TrinkgeldAN, ADMIN → TrinkgeldAG), so nothing about the shape
 * is assumed. That is **not reachable here**: `testApp` calls `TestDatabase.setup()`, which hardcodes
 * an H2 JDBC URL, and it is `testApp` that starts Koin — so there is no service layer on a Postgres
 * connection to call. Every `@Tag("postgres")` suite in this module seeds through Exposed for the
 * same reason.
 *
 * Hand-seeded tip fixtures in this repo have repeatedly modelled shapes production cannot produce —
 * `StatisticsBrowseAttributionTest` seeds `paidAmount = 24.00` NEXT TO `tips = 3.00`, which no closed
 * bill looks like. So this fixture does not invent a shape; it replicates the one the H2 sibling
 * *witnesses* coming out of the real service, and states it as an invariant:
 *
 *  - `tipRecipient` is set **explicitly** on each bill rather than left to the column default, so a
 *    change to that default cannot silently turn both bills into the same case.
 *  - `paidAmount == billAmount + tips`, because `paidAmount` is the TENDERED total
 *    (`BillServiceImpl`: `val paidAmount = payments.sumOf { it.amount }`). That premise is the whole
 *    reason the production expression SUBTRACTS: the tip is inside `paidAmount` exactly once. It is
 *    asserted in [fixtureIsShapedLikeAClosedBill] rather than merely commented, so this file fails
 *    if it ever drifts from the shape the service produces.
 *
 * No `bill_vat_detail` rows are seeded. The first four queries do not read them at all; the two
 * financial-overview ones do, and with none present `vatBreakdown` is empty and `netRevenue` is
 * `grossRevenue` — which is the whole of what the net assertion below claims. The VAT arithmetic
 * itself is `StatisticsFinancialPostgresTest`'s subject, and which rows a tipped bill writes there
 * is P1.1's, covered by `BillTipNotAVatRowTest`.
 *
 * ### Coverage
 *
 * All five call sites of `employeeTipSumExpr()` run here, across the six queries they build —
 * sales-overview headline ([getSalesOverviewSummary][StatisticsRepositoryImpl.getSalesOverviewSummary]),
 * per-table card, per-session, per-employee, and the financial overview's headline + daily series —
 * because the same seeded bills answer all of them and a partial fix would have them reporting
 * different revenue for the same day. The June figures are deliberately reconcilable by eye: the
 * sales-overview headline and the financial-overview headline are both 31.30 on the same fixture.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Employee-tip revenue exclusion runs on real PostgreSQL")
class StatisticsEmployeeTipRevenuePostgresTest {
    private lateinit var pg: PostgresTestDatabase
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))

    private val day = LocalDate(2026, 6, 10)
    private val windowStart = LocalDate(2026, 6, 1).atTime(0, 0, 0)
    private val windowEnd = LocalDate(2026, 6, 30).atTime(23, 59, 59)

    /**
     * The split-payment bill lives in its OWN month, so it cannot move the June figures the other
     * tests pin — bills are attributed by `COALESCE(zbon.end_time, bill.end_time)`, and its Z-Bon
     * closes in July. `windowStart`..[splitWindowEnd] is the window that spans both months.
     */
    private val splitDay = LocalDate(2026, 7, 8)
    private val splitWindowStart = LocalDate(2026, 7, 1).atTime(0, 0, 0)
    private val splitWindowEnd = LocalDate(2026, 7, 31).atTime(23, 59, 59)

    /** Table/employee carrying the EMPLOYEE-tip (TrinkgeldAN) bill. */
    private val staffTable = "PGTIP-A"

    /** Table/employee carrying the OWNER-tip (TrinkgeldAG) bill. */
    private val ownerTable = "PGTIP-B"

    /** Table/employee carrying the July EMPLOYEE-tip bill that is settled by TWO payments. */
    private val splitTable = "PGTIP-C"

    private val staffItem = BigDecimal("19.3000")
    private val staffTip = BigDecimal("7.6000")
    private val ownerItem = BigDecimal("10.0000")
    private val ownerTip = BigDecimal("2.0000")

    /**
     * The same 19.30 + 7.60 tendered as 20.00 + 6.90 that the H2 sibling's split-payment test uses,
     * so the three numbers separating the failure modes — 26.90 pre-fix, 11.70 under a naive
     * fan-out correction, 19.30 correct — mean the same thing on both engines.
     */
    private val splitItem = BigDecimal("19.3000")
    private val splitTip = BigDecimal("7.6000")
    private val splitTenders = listOf(BigDecimal("20.0000"), BigDecimal("6.9000"))

    private var staffTableId = 0L
    private var ownerTableId = 0L

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
        val cash = TestData.createPaymentType(name = "Cash")
        val staff = TestData.createTableWithSession(staffTable)
        val owner = TestData.createTableWithSession(ownerTable)
        val split = TestData.createTableWithSession(splitTable)
        staffTableId = staff.table.id.value
        ownerTableId = owner.table.id.value
        val zbon =
            TestData.createZBon(
                shop = shop,
                employee = admin,
                start = day.atTime(0, 0, 0),
                end = day.atTime(23, 59, 59),
                nummer = 1L,
            )
        val splitZbon =
            TestData.createZBon(
                shop = shop,
                employee = admin,
                start = splitDay.atTime(0, 0, 0),
                end = splitDay.atTime(23, 59, 59),
                nummer = 2L,
            )

        transaction {
            seedClosedBill(
                session = staff.session,
                zbon = zbon,
                billNumber = "PGTIP-1",
                item = staffItem,
                tip = staffTip,
                recipient = TipRecipient.EMPLOYEE,
                cashTypeId = cash.id,
            )
            seedClosedBill(
                session = owner.session,
                zbon = zbon,
                billNumber = "PGTIP-2",
                item = ownerItem,
                tip = ownerTip,
                recipient = TipRecipient.OWNER,
                cashTypeId = cash.id,
            )
            seedClosedBill(
                session = split.session,
                zbon = splitZbon,
                billNumber = "PGTIP-3",
                item = splitItem,
                tip = splitTip,
                recipient = TipRecipient.EMPLOYEE,
                cashTypeId = cash.id,
                tenders = splitTenders,
                at = splitDay.atTime(12, 0, 0),
            )
        }
    }

    @AfterAll
    fun stopDatabase() {
        DatabaseFactory.close()
        pg.close()
    }

    /**
     * The premise the production expression rests on, asserted rather than assumed: the tip sits
     * inside `paidAmount` exactly once, the June bills really do carry different recipients, and the
     * July one really is settled by two payment rows.
     */
    @Test
    fun `fixtureIsShapedLikeAClosedBill`() {
        transaction {
            val bills = Bill.all().associateBy { it.billNumber }
            val staff = bills.getValue("PGTIP-1")
            val owner = bills.getValue("PGTIP-2")

            assertEquals(TipRecipient.EMPLOYEE, staff.tipRecipient, "TrinkgeldAN bill")
            assertEquals(TipRecipient.OWNER, owner.tipRecipient, "TrinkgeldAG bill")
            assertEquals(
                0,
                staff.paidAmount.compareTo(staffItem + staffTip),
                "paidAmount is the TENDERED total, so the tip is inside it exactly once",
            )
            assertEquals(
                0,
                owner.paidAmount.compareTo(ownerItem + ownerTip),
                "paidAmount is the TENDERED total, so the tip is inside it exactly once",
            )

            // The split bill is only a meaningful test while it really does fan out: if it carried
            // ONE payment row, the naive correction would give the right answer too and the test
            // below would pass without proving anything.
            val split = bills.getValue("PGTIP-3")
            val splitPayments =
                PaymentTable
                    .selectAll()
                    .where { PaymentTable.billId eq split.id }
                    .map { it[PaymentTable.amount] }

            assertEquals(TipRecipient.EMPLOYEE, split.tipRecipient, "TrinkgeldAN bill, settled twice")
            assertEquals(2, splitPayments.size, "one bill, TWO payment rows — the fan-out this suite turns on")
            assertEquals(
                0,
                split.paidAmount.compareTo(splitItem + splitTip),
                "paidAmount is the TENDERED total, so the tip is inside it exactly once",
            )
            assertEquals(
                0,
                splitPayments.fold(BigDecimal.ZERO) { sum, amount -> sum + amount }.compareTo(split.paidAmount),
                "the two tenders together settle the bill exactly",
            )
        }
    }

    /**
     * The headline figure. `SUM(CASE ... END)` has to execute on Postgres at all, and the employee
     * tip has to come out of revenue while the owner tip stays in.
     *
     * Σ paidAmount is 38.90; revenue is 31.30 because the 7.60 TrinkgeldAN is not turnover.
     */
    @Test
    fun `getSalesOverviewSummary excludes the employee tip and keeps the owner tip`() {
        val overview =
            transaction {
                repo.getSalesOverviewSummary(windowStart, windowEnd, "EUR")
            }

        assertEquals(
            "31.30",
            overview.totalRevenue,
            "19.30 + 10.00 + 2.00 owner tip — the 7.60 employee tip is not turnover",
        )
        assertEquals("9.60", overview.totalTips, "the tip does not disappear, it stops being revenue")
    }

    @Test
    fun `getPaginatedTableCards excludes the employee tip and keeps the owner tip`() {
        val cards =
            transaction {
                repo.getPaginatedTableCards(windowStart, windowEnd).tableCards.associateBy { it.tableNumber }
            }

        assertEquals("19.30", cards.getValue(staffTable).totalRevenue, "TrinkgeldAN is out of revenue")
        assertEquals("7.60", cards.getValue(staffTable).totalTips, "but still reported as a tip")
        assertEquals("12.00", cards.getValue(ownerTable).totalRevenue, "TrinkgeldAG is taxable turnover")
        assertEquals("2.00", cards.getValue(ownerTable).totalTips)
    }

    @Test
    fun `getSessionSummariesForTable excludes the employee tip and keeps the owner tip`() {
        val (staff, owner) =
            transaction {
                repo.getSessionSummariesForTable(staffTableId, windowStart, windowEnd).single() to
                    repo.getSessionSummariesForTable(ownerTableId, windowStart, windowEnd).single()
            }

        assertEquals("19.30", staff.revenue, "TrinkgeldAN is out of revenue")
        assertEquals("7.60", staff.tips, "but still reported as a tip")
        assertEquals("12.00", owner.revenue, "TrinkgeldAG is taxable turnover")
        assertEquals("2.00", owner.tips)
    }

    @Test
    fun `getPaginatedEmployeeGroups excludes the employee tip and keeps the owner tip`() {
        val groups =
            transaction {
                repo.getPaginatedEmployeeGroups(windowStart, windowEnd).employeeGroups.associateBy { it.employeeNumber }
            }

        // createTableWithSession names each table's cashier "TBL_<tableNumber>", so the two bills
        // land on two different operators.
        val staff = groups.getValue("TBL_$staffTable")
        val owner = groups.getValue("TBL_$ownerTable")

        assertEquals("19.30", staff.totalRevenue, "TrinkgeldAN is out of revenue")
        assertEquals("7.60", staff.totalTips, "but still reported as a tip")
        assertEquals("12.00", owner.totalRevenue, "TrinkgeldAG is taxable turnover")
        assertEquals("2.00", owner.totalTips)
    }

    /**
     * F16. The financial overview's headline, on Postgres, in both directions: the 7.60 TrinkgeldAN
     * leaves "Bruttoumsatz", the 2.00 TrinkgeldAG stays inside it. That is what makes the owner bill
     * a falsifiability control rather than decoration — a CASE matching nothing reports 38.90, a
     * CASE matching every tip reports 29.30, and only one that discriminates reports 31.30.
     *
     * The figure equals `getSalesOverviewSummary`'s headline above on the same fixture, which is the
     * reason this query had to be corrected at all: the two are rendered beside each other.
     */
    @Test
    fun `getFinancialOverview excludes the employee tip and keeps the owner tip`() {
        val financial =
            transaction {
                repo.getFinancialOverview(windowStart, windowEnd, "EUR", null, false)
            }
        val daily = financial.dailyFinancialSummary.getValue(day.toString())

        assertEquals("31.30", financial.grossRevenue, "19.30 + 10.00 + 2.00 owner tip — the 7.60 employee tip is not turnover")
        assertEquals("9.60", financial.totalTips, "the tip does not disappear, it stops being revenue")
        assertEquals(
            "38.90",
            financial.paymentMethodBreakdown.values
                .sumOf { it.totalAmount.toBigDecimal() }
                .toPlainString(),
            "takings keep the tip — gross revenue is below the drawer by exactly the TrinkgeldAN",
        )
        assertEquals("31.30", financial.netRevenue, "no bill_vat_detail rows are seeded, so net carries gross through")
        assertEquals("31.30", daily.grossRevenue, "the daily series is the KPI's own sparkline and must match the headline")
    }

    /**
     * F16, the fan-out gate. `getFinancialOverview` aggregates over
     * `BillTable ⨝ PaymentTable ⨝ PaymentTypeTable`, so this one bill contributes TWO rows; the tip
     * aggregate deliberately runs over bare `BillTable` so the bill-level 7.60 comes out once and
     * not once per payment. Pre-fix this window reads 26.90; a naive fan-out correction reads 11.70.
     *
     * It is also the case an engine difference would hide best — both wrong answers are plausible
     * money, and this is the one shape where H2's leniency and Postgres's strictness could diverge
     * without anything looking broken.
     */
    @Test
    fun `a split payment does not subtract the employee tip once per payment`() {
        val financial =
            transaction {
                repo.getFinancialOverview(splitWindowStart, splitWindowEnd, "EUR", null, false)
            }
        val daily = financial.dailyFinancialSummary.getValue(splitDay.toString())

        assertEquals("19.30", financial.grossRevenue, "the tip sits in the takings once, not once per payment")
        assertEquals("19.30", daily.grossRevenue, "the daily series must not double-subtract it either")
        assertEquals("7.60", financial.totalTips, "the tip is still reported, just not as revenue")
        assertEquals(
            "26.90",
            financial.paymentMethodBreakdown.values
                .sumOf { it.totalAmount.toBigDecimal() }
                .toPlainString(),
            "takings keep the tip: both legs are money that came through the drawer",
        )
    }

    /**
     * F16. The headline and the daily series are separate SQL statements — one flat aggregate, one
     * `GROUP BY` — and only a window spanning both fixtures makes the grouped one produce more than
     * a single bucket. Σ(daily) has to equal the headline: `MonthlyCloseServiceImpl.buildKpis`
     * renders the series as the Bruttoumsatz KPI's sparkline, so a bucket that disagrees with the
     * number printed above it is visible to an operator.
     */
    @Test
    fun `the daily series buckets each day's tip and sums to the headline`() {
        // June 1 – July 31: both fixtures in one window, so the grouped CASE has two day buckets.
        val financial =
            transaction {
                repo.getFinancialOverview(windowStart, splitWindowEnd, "EUR", null, false)
            }
        val daily = financial.dailyFinancialSummary

        assertEquals("50.60", financial.grossRevenue, "65.80 through the drawer, less the two 7.60 employee tips")
        assertEquals(setOf(day.toString(), splitDay.toString()), daily.keys, "one bucket per trading day")
        assertEquals("31.30", daily.getValue(day.toString()).grossRevenue, "June nets out one tip and keeps the owner tip")
        assertEquals("19.30", daily.getValue(splitDay.toString()).grossRevenue, "July nets out the other, across two rows")
        assertEquals(
            financial.grossRevenue,
            daily.values
                .sumOf { it.grossRevenue.toBigDecimal() }
                .toPlainString(),
            "Σ(daily) is the headline, bucketed — the sparkline cannot tell a different story",
        )
    }

    /**
     * One closed, Z-Bon-attributed bill paid by cash, tendered total = item + tip.
     *
     * [recipient] is a required parameter rather than a default so a caller cannot seed a tipped
     * bill without saying which kind it is — the distinction this whole suite turns on.
     *
     * [tenders] settles the bill over several payments instead of one, which is what makes the
     * `PaymentTable` fan-out observable. It must still sum to `item + tip`, and that is CHECKED
     * rather than trusted: a fixture quietly tendering something else would make the split-payment
     * test pass for a reason that has nothing to do with the CASE. [at] is the bill's trading time,
     * so a caller can put a bill on another day and give the daily series a second bucket.
     */
    private fun seedClosedBill(
        session: TableSession,
        zbon: ZBon,
        billNumber: String,
        item: BigDecimal,
        tip: BigDecimal,
        recipient: TipRecipient,
        cashTypeId: EntityID<Long>,
        tenders: List<BigDecimal>? = null,
        at: LocalDateTime = day.atTime(12, 0, 0),
    ) {
        val tendered = item + tip
        val payments = tenders ?: listOf(tendered)
        require(payments.fold(BigDecimal.ZERO) { sum, split -> sum + split }.compareTo(tendered) == 0) {
            "tenders must sum to item + tip — paidAmount is the TENDERED total"
        }

        val bill =
            Bill.new {
                this.employeeId = session.employeeId
                this.tableSession = session
                this.billNumber = billNumber
                this.billAmount = item
                this.paidAmount = tendered
                this.tips = tip
                this.tipRecipient = recipient
                this.startTime = at
                this.endTime = at
                this.isTraining = false
                this.zBon = zbon
            }
        payments.forEach { split ->
            PaymentTable.insert {
                it[billId] = bill.id
                it[paymentTypeId] = cashTypeId
                it[amount] = split
                it[paymentStatus] = PaymentStatus.COMPLETED
                it[paymentTime] = at
                it[currency] = "EUR"
            }
        }
    }
}
