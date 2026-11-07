package org.example.project.features.statistics

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.statistics.data.repository.StatisticsRepositoryImpl
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * E2E-2026-09-15 N1 / plan 2026-09-16 P1.4: an EMPLOYEE tip (TrinkgeldAN) is non-taxable and is NOT
 * turnover, so it must not be reported as revenue.
 *
 * `totalRevenue` was `Σ paidAmount`, and `paidAmount` is the TENDERED total
 * (`BillServiceImpl`: `val paidAmount = payments.sumOf { it.amount }`), so every employee tip
 * inflated it. The e2e run that opened this plan showed **26,90 €** in statistics where the Z-Bon
 * said **19,30 €** — the two numbers below.
 *
 * ### Why `tipRecipient` alone, and not [org.example.project.utils.NonTaxableTipCarrier]
 *
 * The carrier answers a different question — "how much tip is missing from `vatDetails` and must be
 * carried ALONGSIDE it" — and its `Σ vatDetails == paidAmount` guard exists to avoid double-ADDING a
 * tip to a structure that already contains it. Statistics SUBTRACTS from `paidAmount`, which holds
 * the tip exactly once on every bill of either vintage: P1.1 changed only which `BillVatDetail` rows
 * are written, never `paidAmount`. So there is no double-subtraction to guard against here.
 *
 * Reusing the carrier would in fact be WRONG, because it would disagree with the two surfaces this
 * figure has to reconcile against — neither of which consults `vatDetails`:
 *  - `ZBonServiceImpl`: `totalPosRevenue = Σ(billAmount − promotionAmount) + Σ tips of ADMIN closers`
 *  - `DsfinvkExportServiceImpl`: `totalRevenue = Σ billAmount + Σ tips where tipRecipient == OWNER`
 *
 * On a pre-P1.1 bill (19.30 item + 7.60 employee tip, whose tip IS still a 0 % row so the carrier
 * returns ZERO) both of those report 19.30, `tipRecipient` reports 19.30, and the carrier would
 * report 26.90 — a skew on exactly the historical bills it looks like it protects.
 *
 * ### The bills are closed through the real [BillService]
 *
 * Every hand-seeded tipped fixture in this repo has been wrong three times — the sibling
 * `StatisticsBrowseAttributionTest` seeds `paidAmount = 24.00` NEXT TO `tips = 3.00`, a shape
 * production cannot produce. Closing through `createBill` is what makes the `paidAmount` premise
 * above a witnessed fact rather than an assumption; [Surfaces] asserts it on every run.
 *
 * The closer's role is what picks the recipient (`BillRepositoryImpl`: non-ADMIN → TrinkgeldAN,
 * ADMIN → TrinkgeldAG), so an ADMIN closer is how the owner-tip cases are built.
 *
 * All operator-facing revenue surfaces are covered, not just the headline: a partial fix would
 * leave them reporting different revenue for the same day, which is the disagreement P1.2 had to
 * repair across the three DSFinV-K views.
 *
 * ### The financial overview joined this suite as follow-up F5
 *
 * `getFinancialOverview.grossRevenue` sums **`PaymentTable.amount`**, not `paidAmount`, so P1.4's
 * four-site fix did not reach it and it kept reporting the tip as "Bruttoumsatz". It is a revenue
 * figure, not a takings one: the Z-Bon rollup rendered beside it on the Monatsabschluss already
 * excludes employee tips, and `netRevenue` is derived from it by subtracting VAT — so an
 * uncorrected tip, which P1.1 stopped writing as a `BillVatDetail` row, showed up as untaxed net
 * turnover. `refundRevenue` and `totalRefunds` remain takings figures and keep the tip on purpose.
 *
 * Its correction could NOT reuse `revenueExcludingEmployeeTips`: that query joins `PaymentTable`,
 * so it holds one row per payment, and summing the bill-level `tips` across that join would
 * subtract the tip once per payment. The split-payment test below is the gate on exactly that — it
 * is red before the fix (26.90) AND red under the naive fan-out version of it (11.70).
 */
@DisplayName("Revenue figures exclude employee tips (E2E-2026-09-15 N1)")
class StatisticsRevenueExcludesEmployeeTipTest {
    private val deviceId = "stat-emptip-device"
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))

    private val today = currentTimeStamp().date
    private val rangeStart = today.minus(1, DateTimeUnit.DAY).atTime(0, 0, 0)
    private val rangeEnd = today.plus(1, DateTimeUnit.DAY).atTime(23, 59, 59)

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    @Test
    fun `employee tip is reported as tip, not revenue`() {
        val overview = salesOverviewAfter(billAmount = "19.30", employeeTip = "7.60")
        assertEquals("19.30", overview.totalRevenue, "revenue must match the Z-Bon")
        assertEquals("7.60", overview.totalTips)
    }

    @Test
    fun `owner tip stays inside revenue`() {
        val overview = salesOverviewAfter(billAmount = "19.30", ownerTip = "2.00")
        assertEquals("21.30", overview.totalRevenue, "an owner tip is taxable turnover")
        assertEquals("2.00", overview.totalTips)
    }

    /**
     * The per-table, per-session and per-employee cards read the same `paidAmount` sum the headline
     * does. Fixing only the headline would have them report a different revenue for the same day.
     */
    @Test
    fun `the per-table, per-session and per-employee cards exclude the employee tip too`() {
        val surfaces = surfacesAfter(billAmount = "19.30", employeeTip = "7.60")

        assertEquals("19.30", surfaces.tableCard.totalRevenue, "per-table card must agree with the headline")
        assertEquals("19.30", surfaces.session.revenue, "per-session card must agree with the headline")
        assertEquals("19.30", surfaces.employeeGroup.totalRevenue, "per-employee card must agree with the headline")

        // The tip does not disappear — it stops being counted as revenue.
        assertEquals("7.60", surfaces.tableCard.totalTips)
        assertEquals("7.60", surfaces.session.tips)
        assertEquals("7.60", surfaces.employeeGroup.totalTips)
    }

    @Test
    fun `the per-table, per-session and per-employee cards keep an owner tip inside revenue`() {
        val surfaces = surfacesAfter(billAmount = "19.30", ownerTip = "2.00")

        assertEquals("21.30", surfaces.tableCard.totalRevenue, "an owner tip is taxable turnover")
        assertEquals("21.30", surfaces.session.revenue, "an owner tip is taxable turnover")
        assertEquals("21.30", surfaces.employeeGroup.totalRevenue, "an owner tip is taxable turnover")
    }

    /**
     * Follow-up F5. `getFinancialOverview.grossRevenue` reaches the same money through
     * `PaymentTable.amount`, which is the only reason P1.4's four-site fix did not correct it.
     *
     * `dailyFinancialSummary` is asserted next to the headline because it is the SAME figure per
     * day: `MonthlyCloseServiceImpl.buildKpis` renders it as the Bruttoumsatz KPI's own sparkline,
     * so correcting only the headline would leave the sparkline summing to a different number.
     */
    @Test
    fun `the financial overview reports the employee tip as tip, not gross revenue`() {
        val financial = surfacesAfter(billAmount = "19.30", employeeTip = "7.60").financial

        assertEquals("19.30", financial.grossRevenue, "Bruttoumsatz must match the Z-Bon, not the takings")
        assertEquals("7.60", financial.totalTips, "the tip does not disappear, it stops being revenue")
        assertEquals(
            "19.30",
            financial.dailyFinancialSummary.values
                .single()
                .grossRevenue,
            "the daily series is the KPI's own sparkline and must agree with the headline",
        )
    }

    @Test
    fun `the financial overview keeps an owner tip inside gross revenue`() {
        val financial = surfacesAfter(billAmount = "19.30", ownerTip = "2.00").financial

        assertEquals("21.30", financial.grossRevenue, "an owner tip is taxable turnover")
        assertEquals(
            "21.30",
            financial.dailyFinancialSummary.values
                .single()
                .grossRevenue,
            "an owner tip is taxable turnover",
        )
    }

    /**
     * The trap the F5 fix had to avoid, and the reason it does NOT reuse
     * `revenueExcludingEmployeeTips`: `getFinancialOverview` aggregates over
     * `BillTable ⨝ PaymentTable ⨝ PaymentTypeTable`, so a bill settled by TWO payments contributes
     * TWO rows, and summing the bill-level `tips` across that join takes the tip out once per
     * payment.
     *
     * One bill, 19.30 of items + a 7.60 employee tip, tendered as 20.00 + 6.90:
     *  - before the fix            → 26.90 (the tip counted as revenue)
     *  - naive fan-out correction  → 11.70 (26.90 − 2 × 7.60)
     *  - correct                   → 19.30
     *
     * Only the last passes, so this is a gate on BOTH failure modes, not just the original one.
     */
    @Test
    fun `a split payment does not subtract the employee tip twice`() {
        val financial =
            surfacesAfter(
                billAmount = "19.30",
                employeeTip = "7.60",
                paymentSplits = listOf("20.00", "6.90"),
            ).financial

        assertEquals("19.30", financial.grossRevenue, "the tip sits in paidAmount once, not once per payment")
        assertEquals(
            "26.90",
            financial.paymentMethodBreakdown.values
                .sumOf { it.totalAmount.toBigDecimal() }
                .toPlainString(),
            "takings keep the tip: both legs are money that came through the drawer",
        )
    }

    // ============================================
    // Fixture helpers
    // ============================================

    /** The operator-facing revenue figures, read from one closed bill in one run. */
    private data class Surfaces(
        val overview: StatisticsDto.SalesOverviewResponseDto,
        val tableCard: StatisticsDto.TableCardDto,
        val session: StatisticsDto.TableSessionSummaryDto,
        val employeeGroup: StatisticsDto.EmployeeGroupDto,
        val financial: StatisticsDto.FinancialOverviewResponseDto,
    )

    private fun salesOverviewAfter(
        billAmount: String,
        employeeTip: String? = null,
        ownerTip: String? = null,
    ): StatisticsDto.SalesOverviewResponseDto = surfacesAfter(billAmount, employeeTip, ownerTip).overview

    /**
     * Closes ONE bill through the real [BillService] and reads every revenue surface off it.
     *
     * Asserts the fixture's shape before returning: the derived [TipRecipient], and that
     * `paidAmount == billAmount + tip`. The latter is the whole premise of the production change —
     * if `paidAmount` did NOT carry the tip, subtracting it would be a double-subtraction.
     *
     * [paymentSplits] tenders the bill over several payments instead of one, which is what makes
     * the `PaymentTable` fan-out observable; it must still sum to `billAmount + tip`.
     */
    private fun surfacesAfter(
        billAmount: String,
        employeeTip: String? = null,
        ownerTip: String? = null,
        paymentSplits: List<String>? = null,
    ): Surfaces {
        require((employeeTip == null) != (ownerTip == null)) {
            "pass exactly one of employeeTip / ownerTip — the recipient is the point of the fixture"
        }
        val tip = BigDecimal(employeeTip ?: ownerTip!!)
        val item = BigDecimal(billAmount)
        val closerIsAdmin = ownerTip != null
        require(paymentSplits == null || paymentSplits.sumOf { BigDecimal(it) }.compareTo(item + tip) == 0) {
            "paymentSplits must tender exactly billAmount + tip — createBill rejects any other total"
        }

        lateinit var surfaces: Surfaces
        var tableId = 0L
        testApp(
            setupData = { tableId = closeTippedBillThroughTheService(item, tip, closerIsAdmin, paymentSplits) },
        ) { _ ->
            transaction {
                val bill = Bill.all().single()
                assertEquals(
                    if (closerIsAdmin) TipRecipient.OWNER else TipRecipient.EMPLOYEE,
                    bill.tipRecipient,
                    "fixture sanity: BillRepositoryImpl derives the recipient from the closer's role",
                )
                assertEquals(
                    0,
                    bill.paidAmount.compareTo(item + tip),
                    "fixture sanity: paidAmount is the TENDERED total, so the tip is inside it exactly once " +
                        "— got ${bill.paidAmount} for ${item.toPlainString()} + ${tip.toPlainString()}",
                )

                surfaces =
                    Surfaces(
                        overview = repo.getSalesOverviewSummary(rangeStart, rangeEnd, "EUR"),
                        tableCard = repo.getPaginatedTableCards(rangeStart, rangeEnd).tableCards.single(),
                        session = repo.getSessionSummariesForTable(tableId, rangeStart, rangeEnd).single(),
                        employeeGroup = repo.getPaginatedEmployeeGroups(rangeStart, rangeEnd).employeeGroups.single(),
                        financial = repo.getFinancialOverview(rangeStart, rangeEnd, "EUR", null, false),
                    )
            }
        }
        return surfaces
    }

    /**
     * Seeds a one-item session and closes it through the real [BillService], paid in full by cash.
     * Returns the table's id, which [StatisticsRepositoryImpl.getSessionSummariesForTable] needs.
     *
     * Mirrors `DsfinvkEmployeeTipBonkopfUstTest.closeTippedBillThroughTheService`; the only
     * difference is that the closer's role is a parameter, because the owner-tip cases need an ADMIN.
     */
    private fun closeTippedBillThroughTheService(
        item: BigDecimal,
        tip: BigDecimal,
        closerIsAdmin: Boolean,
        paymentSplits: List<String>? = null,
    ): Long {
        val vat19 = TestData.createDefaultVatRates().first()
        TestData.createShopInfo()
        val closer =
            if (closerIsAdmin) {
                TestData.createAdmin(employeeNumber = "STE_ADM", username = "ste_adm")
            } else {
                TestData.createManager(employeeNumber = "STE_MGR", username = "ste_mgr")
            }
        val cash = TestData.createPaymentType("Cash_STE", isCash = true)
        val tws = TestData.createTableWithSession("STE1")
        val category = TestData.createCategory("Stat Emp Tip Cat")
        val product = TestData.createProduct(category.id.value, "Stat Emp Tip Product", "STE", item)

        transaction {
            val now = currentTimeStamp()
            Terminal.new {
                this.deviceId = this@StatisticsRevenueExcludesEmployeeTipTest.deviceId
                this.clientId = "TERM-STE"
                this.registeredAt = now
                this.lastSeenAt = now
            }
        }

        val itemId =
            transaction {
                val session = TableSession.findById(tws.session.id)!!
                // Authorize the closer as the session owner.
                session.employeeId = closer
                OrderItem
                    .new {
                        this.session = session
                        this.product = product
                        this.unitPrice = item
                        this.finalPrice = item
                        this.itemStatus = ItemStatus.OPEN
                        this.vat = vat19
                        this.discount = BigDecimal.ONE
                        this.customPrice = false
                        this.takeAway = false
                        this.transferred = false
                        this.sideNotes = null
                    }.id.value
            }

        runBlocking {
            GlobalContext
                .get()
                .get<BillService>()
                .createBill(
                    employeeId = closer.id.value.toString(),
                    deviceId = deviceId,
                    request =
                        BillDto.CreateBillRequestDto(
                            tableSessionId = tws.session.id.value,
                            orderItems = listOf(itemId),
                            billAmount = item.toPlainString(),
                            tips = tip.toPlainString(),
                            payments =
                                (paymentSplits ?: listOf((item + tip).toPlainString()))
                                    .map { split ->
                                        PaymentDto.PaymentRequest(
                                            amount = split,
                                            paymentTypeId = cash.id.value,
                                        )
                                    },
                        ),
                )
        }

        return tws.table.id.value
    }
}
