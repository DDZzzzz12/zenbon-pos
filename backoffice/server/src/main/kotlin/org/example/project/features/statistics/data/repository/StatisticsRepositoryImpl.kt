/**
 * Implementation of StatisticsRepository using Exposed ORM.
 *
 * Provides concrete implementation of statistics data access operations
 * using Exposed database framework with proper transaction management
 * and comprehensive error handling using the Result pattern.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.statistics.data.repository

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import org.example.project.core.domain.dto.ApiResponseDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.statistics.PaymentMethodSummaryDto
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.domain.dto.statistics.VatSummaryDto
import org.example.project.core.domain.enums.bill.StornoReason
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.database.tables.BillVatDetailTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillSummaries
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.EmployeeHrNames
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.database.tables.CancellationTable
import org.example.project.features.order.database.tables.ItemExtrasTable
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.payment.database.tables.PaymentTypeTable
import org.example.project.features.payment.database.tables.VatTable
import org.example.project.features.product.database.tables.ProductCategoryTable
import org.example.project.features.product.database.tables.ProductExtrasTable
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.example.project.features.promotion.database.tables.PromotionTable
import org.example.project.features.statistics.domain.deltaPercent
import org.example.project.features.statistics.domain.repository.StatisticsRepository
import org.example.project.features.table.database.tables.LocationTypeTable
import org.example.project.features.table.database.tables.RTableTable
import org.example.project.features.table.database.tables.TableSessionTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.features.zBon.database.tables.ZBonTable
import org.example.project.utils.TrainingFilter
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.formatDecimal
import org.example.project.utils.formatter.toBigDecimal
import org.example.project.utils.formatter.toDecimalString
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.hour
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import java.util.UUID
import kotlin.math.ceil

/**
 * Statistics repository implementation using Exposed ORM.
 *
 * Implements comprehensive business intelligence queries for POS system
 * analytics including sales, products, employees, tables, and financial data.
 */
class StatisticsRepositoryImpl(
    private val billSummaries: BillSummaries,
) : StatisticsRepository {
    // Attribute a bill by its Z-Bon close; a bill not yet booked into a Z-Bon (zBonId == null)
    // falls back to its own endTime. Callers must add the LEFT JOIN BillTable→ZBonTable
    // themselves. Both branches are plain table columns, so the expression renders identically in
    // every SELECT/WHERE/GROUP BY it appears in — there is no literal or bound parameter for
    // Postgres to see as a different expression.
    //
    // The window over this expression is (start, end] — the start EXCLUSIVE, as a Z-Bon defines its
    // own period (bill end > previous closing's end). A closed bill is attributed to its closing's
    // end, so an inclusive start equal to the previous closing's end — exactly what "since the last
    // Z-Bon" asks for — counted that whole previous closing again (emu21 NOTE-m: 63 bills instead of
    // 15). Every `attributedTime` filter below uses `greater(startDate)` for that reason.
    private fun attributedTimeExpr() = Coalesce(ZBonTable.endTime, BillTable.endTime)

    // The LEFT JOIN every consumer of buildBillFilterClause must add, so the ZBonTable columns the
    // clause names are actually in scope. LEFT, never INNER: a bill not yet booked into a Z-Bon
    // (zBonId == null) is today's trading and must survive the join to fall back to its own endTime.
    private fun ColumnSet.leftJoinZBon(): Join = join(ZBonTable, JoinType.LEFT, onColumn = BillTable.zBonId, otherColumn = ZBonTable.id)

    // Σ of the tips that are NOT turnover, as a SQL aggregate.
    //
    // An EMPLOYEE tip (TrinkgeldAN) is non-taxable and is not turnover; an OWNER tip (TrinkgeldAG) is
    // taxable turnover, folded across the item rates. `paidAmount` is the TENDERED total
    // (`BillServiceImpl`: `val paidAmount = payments.sumOf { it.amount }`), so it carries the tip
    // either way — this is the part a revenue figure has to take back out.
    //
    // Keyed on `tipRecipient` alone, deliberately NOT on NonTaxableTipCarrier. The carrier answers a
    // different question — "how much tip is missing from `vatDetails` and must be carried alongside
    // it" — and its `Σ vatDetails == paidAmount` guard exists to avoid double-ADDING a tip to a
    // structure that already holds it. Here the tip is SUBTRACTED from `paidAmount`, which holds it
    // exactly once on every bill of either vintage: P1.1 changed which BillVatDetail rows are
    // written, never `paidAmount`. So there is no double-subtraction to guard against.
    //
    // Using the carrier would in fact be wrong, because it would disagree with the surface this
    // figure must reconcile against, which does not read `vatDetails`:
    //   ZBonServiceImpl        `totalPosRevenue = Σ(billAmount − promotionAmount) + Σ tips where tipRecipient == OWNER`
    // (the DSFinV-K preview's `totalRevenue` is Σ of those stored figures). On a pre-P1.1 bill, whose
    // tip IS still a 0 % row so the carrier returns ZERO, it reports the tip-free figure while the
    // carrier would leave the tip inside revenue.
    private fun employeeTipSumExpr() =
        Case()
            .When(BillTable.tipRecipient eq TipRecipient.EMPLOYEE, BillTable.tips)
            .Else(decimalLiteral(BigDecimal.ZERO.setScale(4)))
            .sum()

    // THE "active bill" predicate — the one definition every read site in this file filters by.
    //
    // A bill is active when it is neither a storno reversal (deletedBillId set) nor a cancelled
    // original (deletingReason set). This is the SQL twin of `DrawerCashDerivation.activeBills`, the
    // Z-Bon's rule.
    private fun activeBillOp(): Op<Boolean> =
        BillTable.deletedBillId.isNull() and
            BillTable.deletingReason.isNull()

    // THE "revenue bill" predicate — an active bill that is not a receivable clearing.
    //
    // A clearing bill (`clears_sales_invoice_id` set) collects the money for an outgoing invoice the
    // till opened earlier ("auf Rechnung"). Turnover arose when that invoice was issued, on the
    // original bill; collecting the receivable is not turnover. The Z-Bon therefore leaves it out of
    // `totalPosRevenue` (its `saleBills`, DrawerCashDerivation.saleBills) and DSFinV-K exports it as
    // a Forderungsauflösung. Every REVENUE figure in this file filters by this predicate so it agrees
    // with the Z-Bon. TAKINGS figures (payment-method breakdowns, cash takings) stay on activeBillOp:
    // the money really came in, exactly as the Z-Bon's own Zahlungsarten lines keep it.
    private fun revenueBillOp(): Op<Boolean> = activeBillOp() and BillTable.clearsSalesInvoiceId.isNull()

    // The active receivable clearings — the "Forderungsausgleich" a takings figure holds and a
    // revenue figure does not. Σ billAmount over it is the Z-Bon's `totalReceivableCleared`.
    private fun receivableClearingOp(): Op<Boolean> = activeBillOp() and BillTable.clearsSalesInvoiceId.isNotNull()

    // Σ paidAmount of the revenue bills (revenueBillOp) inside whatever row set the query selects,
    // for the grouped browse queries, which select every receipt of the window (Stornos included, so
    // the rows' bills can be listed). A Storno exactly negates its original's paidAmount, so counting
    // active bills only gives what netting every row gave — except for a receivable clearing, which
    // netting would count as revenue. Only valid over a query with ONE row per bill.
    private fun revenuePaidSumExpr() =
        Case()
            .When(revenueBillOp(), BillTable.paidAmount)
            .Else(decimalLiteral(BigDecimal.ZERO.setScale(4)))
            .sum()

    // employeeTipSumExpr scoped to the revenue bills, the tip twin of revenuePaidSumExpr.
    private fun revenueEmployeeTipSumExpr() =
        Case()
            .When(revenueBillOp() and (BillTable.tipRecipient eq TipRecipient.EMPLOYEE), BillTable.tips)
            .Else(decimalLiteral(BigDecimal.ZERO.setScale(4)))
            .sum()

    // THE "refund" predicate — a Storno bill that handed money back. A Zahlartwechsel Storno refunds
    // nothing — its sale is booked again at once — so it is left out; the Z-Bon keeps it in its
    // fiscal refund figure and breaks it out as "davon Zahlartwechsel". A NULL storno reason is an
    // ordinary Storno, and SQL's `<>` is not true for NULL, hence the explicit isNull() arm.
    private fun refundingStornoOp(): Op<Boolean> =
        BillTable.deletedBillId.isNotNull() and
            (BillTable.stornoReason.isNull() or (BillTable.stornoReason neq StornoReason.ZAHLARTWECHSEL))

    // Counts the sale bills (see revenueBillOp) inside whatever row set the query selects. Counting
    // every row showed an employee "5 Rechnungen" for 3 sales plus the cancelled bill and its
    // reversal (E2E-2026-09-18 #20). A receivable clearing is not a sale either: it would divide the
    // Ø Bon by a receipt that carries no turnover. A SUM over no rows is NULL, so readers take `?: 0`.
    //
    // Only valid over a query with ONE row per bill: over a BillTable ⨝ PaymentTable join it would
    // count payments, not bills.
    private fun saleBillCountExpr() =
        Case()
            .When(revenueBillOp(), intLiteral(1))
            .Else(intLiteral(0))
            .sum()

    // Σ paidAmount less the employee tips inside it — the revenue figure every operator-facing card
    // reports. Exposed keys a ResultRow by the expression's rendered SQL, so the freshly built
    // `paidAmount.sum()` here resolves to the caller's selected column exactly as it does at every
    // other read site in this file.
    private fun ResultRow.revenueExcludingEmployeeTips(employeeTipSum: Expression<BigDecimal?>): BigDecimal =
        revenueExcludingEmployeeTips(BillTable.paidAmount.sum(), employeeTipSum)

    // The same figure over explicitly selected sum expressions — the grouped browse queries pass
    // revenuePaidSumExpr/revenueEmployeeTipSumExpr, which select every receipt of the window.
    private fun ResultRow.revenueExcludingEmployeeTips(
        paidSum: Expression<BigDecimal?>,
        employeeTipSum: Expression<BigDecimal?>,
    ): BigDecimal = (this[paidSum] ?: BigDecimal.ZERO) - (this[employeeTipSum] ?: BigDecimal.ZERO)

    private fun buildBillFilterClause(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employee: Employee?,
        trainingOnly: Boolean,
        paymentTypes: List<String>?,
    ): Op<Boolean> {
        // Z-Bon-close attribution, the same fiscal convention getSalesOverviewSummary and the catalog
        // aggregates use: a session opening before midnight and closing the next day is the closing
        // day's business. Every query that consumes this clause MUST leftJoinZBon() — the clause
        // names ZBonTable.endTime, so an unjoined query would emit SQL referencing a table that is
        // not in its FROM.
        val attributedTime = attributedTimeExpr()
        var filterClause: Op<Boolean> =
            attributedTime.greater(startDate) and
                attributedTime.lessEq(endDate) and TrainingFilter.billFilter(trainingOnly)
        employee?.let {
            filterClause = filterClause and BillTable.employeeId.eq(employee.id)
        }
        if (!paymentTypes.isNullOrEmpty()) {
            filterClause = filterClause and paidWithOp(paymentTypes)
        }
        return filterClause
    }

    // THE Zahlungsart filter: a bill matches when the tender of the sale it belongs to uses one of
    // [paymentTypes]. A split sale matches each of its methods. A sale and its Storno (deletedBillId)
    // match by the sale's tender, so a cancelled sale and its Storno stay side by side under the
    // method that was refunded, and the list's paidAmount sum still nets them to zero.
    private fun paidWithOp(paymentTypes: List<String>): Op<Boolean> {
        val saleIds = saleIdsPaidWith(paymentTypes)
        return BillTable.id.inList(saleIds) or
            BillTable.deletedBillId.inList(saleIds)
    }

    // Ids of the bills with a payment row of one of [paymentTypes] — a cancelled original included,
    // whose rows stay on it (REFUNDED), so its Storno is found through deletedBillId above.
    private fun saleIdsPaidWith(paymentTypes: List<String>): List<EntityID<Long>> =
        PaymentTable
            .innerJoin(PaymentTypeTable, { PaymentTable.paymentTypeId }, { PaymentTypeTable.id })
            .select(PaymentTable.billId)
            .where { PaymentTypeTable.name.inList(paymentTypes) }
            .map { it[PaymentTable.billId] }
            .distinct()

    // =========================== PAGINATED STATISTICS ===========================

    override fun getSalesOverviewSummary(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        currency: String,
        employee: Employee?,
        trainingOnly: Boolean,
    ): StatisticsDto.SalesOverviewResponseDto {
        // Attribute each bill by its Z-Bon's close, not its own timestamp: a session that opens
        // before midnight and closes the next day is fiscally the closing day's business, its late
        // clock time included. A closed bill (zBonId set) is booked to ZBonTable.endTime's day; an
        // unassigned bill (no Z-Bon yet) falls back to its own BillTable.endTime, which the
        // requested window either contains or not. The join is LEFT and the attribution COALESCEd so
        // unassigned bills are never dropped. This is the same fiscal convention ZBonRepositoryImpl
        // already closes the day by (its endTime filter).
        val attributedTime = attributedTimeExpr()
        val source =
            BillTable.join(
                ZBonTable,
                JoinType.LEFT,
                onColumn = BillTable.zBonId,
                otherColumn = ZBonTable.id,
            )

        // Build base bill filter clause (exclude training bills)
        var baseBillClause: Op<Boolean> =
            attributedTime.greater(startDate) and
                attributedTime.lessEq(endDate) and TrainingFilter.billFilter(trainingOnly)
        employee?.let {
            baseBillClause = baseBillClause and BillTable.employeeId.eq(employee.id)
        }

        // Exclude storno bills and cancelled originals (activeBillOp) from the payment breakdown, and
        // additionally the receivable clearings (revenueBillOp) from the revenue totals: the Z-Bon's
        // Umsatz leaves a clearing out while its Zahlungsarten keep its payment.
        val activeBillClause = baseBillClause and activeBillOp()
        val revenueBillClause = baseBillClause and revenueBillOp()

        // Aggregate revenue-bill (non-storno, non-cancelled, non-clearing) totals
        val employeeTipSum = employeeTipSumExpr()
        // Sale bills, not rows: the Ø Bon both clients and buildComparison derive
        // (totalRevenue / totalBillCount) must divide by sales.
        val saleBillCount = saleBillCountExpr()
        val billAggregates =
            source
                .select(
                    BillTable.paidAmount.sum(),
                    BillTable.billAmount.sum(),
                    BillTable.tips.sum(),
                    employeeTipSum,
                    BillTable.discountAmount.sum(),
                    BillTable.promotionAmount.sum(),
                    saleBillCount,
                ).where { revenueBillClause }
                .singleOrNull()

        // Σ of the receivable clearings: in the payment breakdown below, not in the revenue above —
        // the Z-Bon's `totalReceivableCleared`, so a client can show why the two differ.
        val clearedSum = BillTable.billAmount.sum()
        val receivableCleared =
            source
                .select(clearedSum)
                .where { baseBillClause and receivableClearingOp() }
                .singleOrNull()
                ?.get(clearedSum) ?: BigDecimal.ZERO

        // Revenue, not takings: the employee tips inside paidAmount are not turnover. `totalTips`
        // below still reports the full tip — the tip does not disappear, it stops being revenue.
        val totalRevenue = billAggregates?.revenueExcludingEmployeeTips(employeeTipSum) ?: BigDecimal.ZERO
        val billAmounts = billAggregates?.get(BillTable.billAmount.sum()) ?: BigDecimal.ZERO
        val tipAmount = billAggregates?.get(BillTable.tips.sum()) ?: BigDecimal.ZERO
        // "Rabatte" = manual line discounts + promotion deductions (bill-wide and item promotions),
        // over the same active bills as the revenue — the Z-Bon's own `totalDiscounts` rule
        // (ZBonServiceImpl: Σ activeBills (discountAmount + promotionAmount)). Summing
        // discountAmount alone reported 0.00 for a day whose only reductions were promotions.
        // activeBillOp drops a stornoed original and its Storno, so a reversed sale's promotion is
        // not counted, and a Zahlartwechsel's is counted once, through its Neubuchung.
        val totalDiscounts =
            (billAggregates?.get(BillTable.discountAmount.sum()) ?: BigDecimal.ZERO) +
                (billAggregates?.get(BillTable.promotionAmount.sum()) ?: BigDecimal.ZERO)
        val totalBillCount = billAggregates?.get(saleBillCount) ?: 0

        // Aggregate refund totals from storno/reverse bills (refundingStornoOp): a Zahlartwechsel
        // Storno refunds nothing — its sale is booked again at once.
        val stornoBillClause = baseBillClause and refundingStornoOp()
        val refundAggregates =
            source
                .select(BillTable.paidAmount.sum())
                .where { stornoBillClause }
                .singleOrNull()
        // Storno bills have negative amounts, negate to get positive refund total
        val refundAmount = (refundAggregates?.get(BillTable.paidAmount.sum()) ?: BigDecimal.ZERO).negate()

        // Count non-training sessions in the date range
        var sessionClause: Op<Boolean> =
            TableSessionTable.startTime.greaterEq(startDate) and
                TableSessionTable.startTime.lessEq(endDate) and TrainingFilter.sessionFilter(trainingOnly)
        employee?.let {
            sessionClause = sessionClause and TableSessionTable.employeeId.eq(employee.id)
        }
        val totalSessionCount =
            TableSessionTable
                .select(TableSessionTable.id.count())
                .where { sessionClause }
                .singleOrNull()
                ?.get(TableSessionTable.id.count())
                ?.toInt() ?: 0

        // Calculate payment method summaries from PaymentTable. Chained off `source` so the
        // Z-Bon-attributed activeBillClause below can reference ZBonTable.endTime.
        val paymentJoin =
            source
                .innerJoin(PaymentTable, { BillTable.id }, { PaymentTable.billId })
                .innerJoin(PaymentTypeTable, { PaymentTable.paymentTypeId }, { PaymentTypeTable.id })

        val revenueByPaymentType =
            paymentJoin
                .select(
                    PaymentTypeTable.name,
                    PaymentTypeTable.enName,
                    PaymentTypeTable.chName,
                    PaymentTable.amount.sum(),
                    PaymentTable.amount.count(),
                ).where { activeBillClause }
                .groupBy(PaymentTypeTable.name, PaymentTypeTable.enName, PaymentTypeTable.chName)
                .map { row ->
                    PaymentDto.PaymentSummary(
                        amount = formatDecimal(row[PaymentTable.amount.sum()] ?: BigDecimal.ZERO),
                        paymentCount = row[PaymentTable.amount.count()].toInt(),
                        paymentTypeDE = row[PaymentTypeTable.name],
                        paymentTypeEN = row[PaymentTypeTable.enName],
                        paymentTypeCH = row[PaymentTypeTable.chName],
                    )
                }

        // Calculate non-training cancellation amount
        var cancelClause: Op<Boolean> =
            CancellationTable.createdAt.greaterEq(startDate) and
                (CancellationTable.createdAt.lessEq(endDate)) and TrainingFilter.cancellationFilter(trainingOnly)
        employee?.let {
            cancelClause = cancelClause and (CancellationTable.employeeId eq employee.id)
        }
        val canceledAmount = Cancellation.find { cancelClause }.sumOf { it.orderItem.finalPrice }

        // Calculate open items amount
        val tableSessions = TableSession.find { sessionClause }.toList()
        val openItemAmount =
            tableSessions
                .flatMap { it.orderItems }
                .filter { it.itemStatus == ItemStatus.OPEN }
                .sumOf { it.finalPrice }

        return StatisticsDto.SalesOverviewResponseDto(
            period = "$startDate to $endDate",
            totalRevenue = totalRevenue.toDecimalString(),
            billedRevenue = billAmounts.toDecimalString(),
            canceledRevenue = canceledAmount.toDecimalString(),
            refundRevenue = refundAmount.toDecimalString(),
            openItemAmount = openItemAmount.toDecimalString(),
            totalDiscounts = totalDiscounts.toDecimalString(),
            totalTips = tipAmount.toDecimalString(),
            revenueByPaymentMethod = revenueByPaymentType,
            receivableCleared = receivableCleared.toDecimalString(),
            totalBillCount = totalBillCount,
            totalSessionCount = totalSessionCount,
            currency = currency,
        )
    }

    override fun getPaginatedBills(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employee: Employee?,
        page: Int,
        pageSize: Int,
        sort: StatisticsDto.StatisticsSortOption,
        trainingOnly: Boolean,
        paymentTypes: List<String>?,
    ): StatisticsDto.PaginatedBillsResponseDto {
        val filterClause = buildBillFilterClause(startDate, endDate, employee, trainingOnly, paymentTypes)
        // All three queries below read the same clause, so all three need the Z-Bon join. The join is
        // many-to-one on ZBonTable's primary key, so it adds no rows: the count, the paidAmount/tips
        // sums and the page all stay one row per bill.
        val source = BillTable.leftJoinZBon()

        // Count total items for pagination
        val totalItems =
            source
                .select(BillTable.id.count())
                .where { filterClause }
                .singleOrNull()
                ?.get(BillTable.id.count()) ?: 0L

        // Cross-page aggregation totals for the aggregation summary card
        val aggregates =
            source
                .select(BillTable.paidAmount.sum(), BillTable.tips.sum())
                .where { filterClause }
                .singleOrNull()
        val totalAmount = aggregates?.get(BillTable.paidAmount.sum()) ?: BigDecimal.ZERO
        val totalTips = aggregates?.get(BillTable.tips.sum()) ?: BigDecimal.ZERO

        // Determine sort order. Includes its own unique tiebreaker — see sortToOrderBy.
        val orderTerms = sortToOrderBy(sort)

        // Fetch the page over the joined source and wrap it back into Bill entities. A bare
        // Bill.find would query BillTable alone, which cannot resolve the ZBonTable column the
        // filter names; wrapRows is the same trick EmployeeInfoRepositoryImpl uses to run a DAO
        // read over a join.
        // endTime/paidAmount are non-unique, so OFFSET pagination needs a unique tiebreaker
        // (BillTable.id) — without it, rows with equal sort keys can shuffle between page
        // queries, causing bills to be duplicated or skipped across pages.
        val bills =
            Bill
                .wrapRows(
                    source
                        .select(BillTable.columns)
                        .where { filterClause }
                        .orderBy(*orderTerms)
                        .limit(pageSize)
                        .offset((page * pageSize).toLong()),
                ).toList()
                // Batched: the rows' relation links and current tender load once per page.
                .let(billSummaries::summariesOf)

        val pagination = buildPaginationInfo(page, pageSize, totalItems)

        return StatisticsDto.PaginatedBillsResponseDto(
            bills = bills,
            totalAmount = totalAmount.toDecimalString(),
            totalTips = totalTips.toDecimalString(),
            pagination = pagination,
        )
    }

    override fun getPaymentRecords(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employee: Employee?,
        page: Int,
        pageSize: Int,
        sort: StatisticsDto.StatisticsSortOption,
        trainingOnly: Boolean,
    ): StatisticsDto.PaginatedPaymentsResponseDto {
        // Join payment with bill and payment type for filtering and field access
        val paymentBillJoin =
            PaymentTable
                .innerJoin(BillTable, { PaymentTable.billId }, { BillTable.id })
                .innerJoin(PaymentTypeTable, { PaymentTable.paymentTypeId }, { PaymentTypeTable.id })

        // Build filter clause: payment time within range, exclude training bills
        var filterClause: Op<Boolean> =
            PaymentTable.paymentTime.greaterEq(startDate) and
                PaymentTable.paymentTime.lessEq(endDate) and TrainingFilter.billFilter(trainingOnly)
        employee?.let {
            filterClause = filterClause and BillTable.employeeId.eq(employee.id)
        }

        // Count total items for pagination
        val totalItems =
            paymentBillJoin
                .select(PaymentTable.id.count())
                .where { filterClause }
                .singleOrNull()
                ?.get(PaymentTable.id.count()) ?: 0L

        // Determine sort order (map to payment table columns)
        val (sortColumn, sortOrder) =
            when (sort) {
                StatisticsDto.StatisticsSortOption.RECENT -> PaymentTable.paymentTime to SortOrder.DESC
                StatisticsDto.StatisticsSortOption.OLDEST -> PaymentTable.paymentTime to SortOrder.ASC
                StatisticsDto.StatisticsSortOption.HIGHEST -> PaymentTable.amount to SortOrder.DESC
                StatisticsDto.StatisticsSortOption.LOWEST -> PaymentTable.amount to SortOrder.ASC
            }

        // Fetch paginated payment records
        val payments =
            paymentBillJoin
                .selectAll()
                .where { filterClause }
                .orderBy(sortColumn to sortOrder)
                .limit(pageSize)
                .offset((page * pageSize).toLong())
                .map { row ->
                    StatisticsDto.PaymentRecordDto(
                        id = row[PaymentTable.id].value,
                        billId = row[BillTable.id].value,
                        billNumber = row[BillTable.billNumber],
                        paymentTypeDE = row[PaymentTypeTable.name],
                        paymentTypeEN = row[PaymentTypeTable.enName],
                        paymentTypeCH = row[PaymentTypeTable.chName],
                        amount = row[PaymentTable.amount].toDecimalString(),
                        currency = row[PaymentTable.currency],
                        paymentStatus = row[PaymentTable.paymentStatus],
                        paymentTime = row[PaymentTable.paymentTime].toString(),
                        transactionId = row[PaymentTable.transactionId],
                        refundTime = row[PaymentTable.refundTime]?.toString(),
                    )
                }

        val pagination = buildPaginationInfo(page, pageSize, totalItems)

        return StatisticsDto.PaginatedPaymentsResponseDto(
            payments = payments,
            pagination = pagination,
        )
    }

    override fun getPaginatedTableCards(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employee: Employee?,
        page: Int,
        pageSize: Int,
        trainingOnly: Boolean,
        paymentTypes: List<String>?,
    ): StatisticsDto.PaginatedTableCardsResponseDto {
        val filterClause = buildBillFilterClause(startDate, endDate, employee, trainingOnly, paymentTypes)

        // Both queries below read filterClause, so the Z-Bon join belongs on the shared source. It is
        // many-to-one on ZBonTable's primary key, so neither the countDistinct nor the per-table
        // paidAmount/tips/bill-count sums gain a row from it.
        val billSessionJoin =
            BillTable
                .innerJoin(TableSessionTable, { BillTable.tableSession }, { TableSessionTable.id })
                .innerJoin(RTableTable, { TableSessionTable.table }, { RTableTable.id })
                .innerJoin(LocationTypeTable, { RTableTable.locationTypeId }, { LocationTypeTable.id })
                .leftJoinZBon()

        val totalDistinctTables =
            billSessionJoin
                .select(RTableTable.id.countDistinct())
                .where { filterClause }
                .singleOrNull()
                ?.get(RTableTable.id.countDistinct()) ?: 0L

        val sessionCountExpr = TableSessionTable.id.countDistinct()
        // Revenue bills only (revenuePaidSumExpr): a receivable clearing shares its original's table
        // session, and netting every row counted the sale twice — once billed, once collected.
        val revenuePaidSum = revenuePaidSumExpr()
        val employeeTipSum = revenueEmployeeTipSumExpr()
        val tableCardAggregates =
            billSessionJoin
                .select(
                    RTableTable.id,
                    RTableTable.tableNumber,
                    LocationTypeTable.name,
                    LocationTypeTable.englishName,
                    LocationTypeTable.chineseName,
                    LocationTypeTable.sortOrder,
                    LocationTypeTable.isVirtual,
                    revenuePaidSum,
                    BillTable.tips.sum(),
                    employeeTipSum,
                    BillTable.id.count(),
                    sessionCountExpr,
                ).where { filterClause }
                .groupBy(
                    RTableTable.id,
                    RTableTable.tableNumber,
                    LocationTypeTable.name,
                    LocationTypeTable.englishName,
                    LocationTypeTable.chineseName,
                    LocationTypeTable.sortOrder,
                    LocationTypeTable.isVirtual,
                ).orderBy(LocationTypeTable.sortOrder to SortOrder.ASC, RTableTable.tableNumber to SortOrder.ASC)
                .limit(pageSize)
                .offset((page * pageSize).toLong())
                .map { row ->
                    StatisticsDto.TableCardDto(
                        tableId = row[RTableTable.id].value,
                        tableNumber = row[RTableTable.tableNumber],
                        locationTypeName = row[LocationTypeTable.name],
                        locationTypeEN = row[LocationTypeTable.englishName],
                        locationTypeCH = row[LocationTypeTable.chineseName],
                        locationTypeSortOrder = row[LocationTypeTable.sortOrder],
                        isVirtual = row[LocationTypeTable.isVirtual],
                        totalRevenue = row.revenueExcludingEmployeeTips(revenuePaidSum, employeeTipSum).toDecimalString(),
                        totalTips = (row[BillTable.tips.sum()] ?: BigDecimal.ZERO).toDecimalString(),
                        totalBillCount = row[BillTable.id.count()].toInt(),
                        sessionCount = row[sessionCountExpr].toInt(),
                    )
                }

        val pagination = buildPaginationInfo(page, pageSize, totalDistinctTables)

        return StatisticsDto.PaginatedTableCardsResponseDto(
            tableCards = tableCardAggregates,
            pagination = pagination,
        )
    }

    override fun getSessionSummariesForTable(
        tableId: Long,
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employee: Employee?,
        trainingOnly: Boolean,
        paymentTypes: List<String>?,
    ): List<StatisticsDto.TableSessionSummaryDto> {
        val filterClause = buildBillFilterClause(startDate, endDate, employee, trainingOnly, paymentTypes)

        val sessionBillJoin =
            BillTable
                .innerJoin(TableSessionTable, { BillTable.tableSession }, { TableSessionTable.id })
                .leftJoinZBon()

        // Revenue bills only — see getPaginatedTableCards.
        val revenuePaidSum = revenuePaidSumExpr()
        val employeeTipSum = revenueEmployeeTipSumExpr()
        val sessionsWithBills =
            sessionBillJoin
                .select(
                    TableSessionTable.id,
                    TableSessionTable.startTime,
                    TableSessionTable.endTime,
                    revenuePaidSum,
                    BillTable.tips.sum(),
                    employeeTipSum,
                    BillTable.id.count(),
                ).where { filterClause and (TableSessionTable.table eq tableId) }
                .groupBy(TableSessionTable.id, TableSessionTable.startTime, TableSessionTable.endTime)
                .orderBy(TableSessionTable.startTime to SortOrder.ASC)
                .map { row ->
                    object {
                        val sessionId = row[TableSessionTable.id].value
                        val startTime = row[TableSessionTable.startTime]
                        val endTime = row[TableSessionTable.endTime]
                        val revenue = row.revenueExcludingEmployeeTips(revenuePaidSum, employeeTipSum)
                        val tips = row[BillTable.tips.sum()] ?: BigDecimal.ZERO
                        val billCount = row[BillTable.id.count()].toInt()
                    }
                }

        val sessionIds = sessionsWithBills.map { it.sessionId }
        val bills =
            if (sessionIds.isNotEmpty()) {
                // Same clause, so the same Z-Bon join — a bare Bill.find cannot resolve
                // ZBonTable.endTime. See getPaginatedBills for why wrapRows.
                Bill
                    .wrapRows(
                        BillTable
                            .leftJoinZBon()
                            .select(BillTable.columns)
                            .where { filterClause and BillTable.tableSession.inList(sessionIds) }
                            .orderBy(BillTable.endTime to SortOrder.ASC),
                    ).toList()
                    .groupBy { it.tableSession.id.value }
            } else {
                emptyMap()
            }

        // Batched across every session's bills: the relation links load once, not per row.
        val summaries =
            bills.values
                .flatten()
                .let(billSummaries::summariesOf)
                .associateBy { it.billId }

        return sessionsWithBills.map { session ->
            StatisticsDto.TableSessionSummaryDto(
                sessionId = session.sessionId,
                startTime = session.startTime.toString(),
                endTime = session.endTime?.toString(),
                revenue = session.revenue.toDecimalString(),
                tips = session.tips.toDecimalString(),
                billCount = session.billCount,
                bills = bills[session.sessionId]?.map { summaries.getValue(it.id.value) } ?: emptyList(),
            )
        }
    }

    override fun getPaginatedEmployeeGroups(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employee: Employee?,
        page: Int,
        pageSize: Int,
        sort: StatisticsDto.StatisticsSortOption,
        trainingOnly: Boolean,
        paymentTypes: List<String>?,
        hrNames: Boolean,
    ): StatisticsDto.PaginatedEmployeeGroupsResponseDto {
        val filterClause = buildBillFilterClause(startDate, endDate, employee, trainingOnly, paymentTypes)

        // Both queries below read filterClause, so the Z-Bon join belongs on the shared source. It is
        // many-to-one on ZBonTable's primary key, so the employee countDistinct and the per-employee
        // paidAmount/tips/bill-count sums are unaffected by it.
        val billEmployeeJoin =
            BillTable
                .innerJoin(EmployeeTable, { BillTable.employeeId }, { EmployeeTable.id })
                .leftJoinZBon()

        // Step 1: Get distinct employees with aggregates, paginated
        val totalDistinctEmployees =
            billEmployeeJoin
                .select(EmployeeTable.id.countDistinct())
                .where { filterClause }
                .singleOrNull()
                ?.get(EmployeeTable.id.countDistinct()) ?: 0L

        // Revenue bills only (revenuePaidSumExpr): an employee's Umsatz must not count the
        // receivable clearings they collected — the Z-Bon's per-employee rows do not.
        val revenuePaidSum = revenuePaidSumExpr()
        val employeeTipSum = revenueEmployeeTipSumExpr()
        val activeBillCount = saleBillCountExpr()
        val employeeGroupAggregates =
            billEmployeeJoin
                .select(
                    EmployeeTable.id,
                    EmployeeTable.employeeNumber,
                    EmployeeTable.username,
                    revenuePaidSum,
                    BillTable.tips.sum(),
                    employeeTipSum,
                    activeBillCount,
                ).where { filterClause }
                .groupBy(EmployeeTable.id, EmployeeTable.employeeNumber, EmployeeTable.username)
                .orderBy(EmployeeTable.employeeNumber, SortOrder.ASC)
                .limit(pageSize)
                .offset((page * pageSize).toLong())
                .map { row ->
                    object {
                        val empId = row[EmployeeTable.id].value
                        val empNumber = row[EmployeeTable.employeeNumber]

                        // `employeeName` keeps naming the operator by username (the wire field older
                        // clients read); the shared login-less device account gets its own label
                        // rather than the raw `terminal-device`. `displayName` is resolved below.
                        val empName = TerminalEmployee.displayName(row[EmployeeTable.username])
                        val totalRevenue = row.revenueExcludingEmployeeTips(revenuePaidSum, employeeTipSum)
                        val totalTips = row[BillTable.tips.sum()] ?: BigDecimal.ZERO
                        val billCount = row[activeBillCount] ?: 0
                    }
                }

        // Step 2: Fetch bills for current page's employees
        val employeeIds = employeeGroupAggregates.map { it.empId }
        // The HR name (first + last) for the page in one query, as the staff directory does, and only
        // for a caller who may read it; the username label is the fallback, so `displayName` is never
        // blank. Statistics only: nothing signed, booked or exported reads this DTO.
        val names = operatorHrNames(employeeIds, hrNames)
        val employeeGroups =
            if (employeeIds.isNotEmpty()) {
                val orderTerms = sortToOrderBy(sort)
                // Same clause, so the same Z-Bon join — a bare Bill.find cannot resolve
                // ZBonTable.endTime. See getPaginatedBills for why wrapRows.
                val billsForEmployees =
                    Bill
                        .wrapRows(
                            BillTable
                                .leftJoinZBon()
                                .select(BillTable.columns)
                                .where { filterClause and BillTable.employeeId.inList(employeeIds) }
                                .orderBy(*orderTerms),
                        ).toList()

                val billsByEmployee = billsForEmployees.groupBy { it.employeeId.id.value }
                // Batched across the page's employees: the relation links load once, not per row.
                val summaries = billSummaries.summariesOf(billsForEmployees).associateBy { it.billId }

                employeeGroupAggregates.map { agg ->
                    StatisticsDto.EmployeeGroupDto(
                        employeeNumber = agg.empNumber,
                        employeeName = agg.empName,
                        displayName = names[agg.empId] ?: agg.empName,
                        totalRevenue = agg.totalRevenue.toDecimalString(),
                        totalTips = agg.totalTips.toDecimalString(),
                        billCount = agg.billCount,
                        bills = billsByEmployee[agg.empId]?.map { summaries.getValue(it.id.value) } ?: emptyList(),
                    )
                }
            } else {
                emptyList()
            }

        val pagination = buildPaginationInfo(page, pageSize, totalDistinctEmployees)

        return StatisticsDto.PaginatedEmployeeGroupsResponseDto(
            employeeGroups = employeeGroups,
            pagination = pagination,
        )
    }

    override fun getPaginatedSessions(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employee: Employee?,
        page: Int,
        pageSize: Int,
        trainingOnly: Boolean,
        completed: Boolean?,
        hrNames: Boolean,
    ): StatisticsDto.PaginatedSessionsResponseDto {
        // Build filter clause (exclude training sessions)
        var filterClause: Op<Boolean> =
            TableSessionTable.startTime.greaterEq(startDate) and
                TableSessionTable.startTime.lessEq(endDate) and TrainingFilter.sessionFilter(trainingOnly)
        employee?.let {
            filterClause = filterClause and TableSessionTable.employeeId.eq(employee.id)
        }
        completed?.let {
            filterClause = filterClause and TableSessionTable.completed.eq(it)
        }

        // Count total sessions
        val totalItems =
            TableSessionTable
                .select(TableSessionTable.id.count())
                .where { filterClause }
                .singleOrNull()
                ?.get(TableSessionTable.id.count()) ?: 0L

        // Fetch paginated sessions
        val sessions =
            TableSession
                .find { filterClause }
                .orderBy(TableSessionTable.startTime to SortOrder.DESC)
                .limit(pageSize)
                .offset((page * pageSize).toLong())
                .map { session -> billSummaries.sessionSummaryOf(session, employee, hrNames) }

        val pagination = buildPaginationInfo(page, pageSize, totalItems)

        return StatisticsDto.PaginatedSessionsResponseDto(
            sessions = sessions,
            pagination = pagination,
        )
    }

    override fun getCanceledItems(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        employee: Employee?,
        trainingOnly: Boolean,
        hrNames: Boolean,
    ): StatisticsDto.CanceledItemsResponseDto {
        var filterClause: Op<Boolean> =
            (CancellationTable.createdAt greaterEq startDate) and
                (CancellationTable.createdAt lessEq endDate) and
                TrainingFilter.cancellationFilter(trainingOnly)

        employee?.let {
            filterClause = filterClause and (CancellationTable.employeeId eq it.id)
        }

        // Single query joining cancellation -> order item -> product/employee (+ optional bill)
        // instead of lazy-loading orderItem, bill, product and employee per cancellation (N+3 queries).
        // OrderItemTable has two BillTable references (billId, duplicatedFromBillId), so the
        // bill join columns must be given explicitly; leftJoin because billId is nullable.
        val rows =
            CancellationTable
                .innerJoin(OrderItemTable, { CancellationTable.orderItemId }, { OrderItemTable.id })
                .innerJoin(ProductTable, { OrderItemTable.productId }, { ProductTable.id })
                .innerJoin(EmployeeTable, { CancellationTable.employeeId }, { EmployeeTable.id })
                .leftJoin(BillTable, { OrderItemTable.billId }, { BillTable.id })
                .select(
                    ProductTable.name,
                    OrderItemTable.unitPrice,
                    OrderItemTable.finalPrice,
                    BillTable.billNumber,
                    EmployeeTable.id,
                    EmployeeTable.username,
                    CancellationTable.createdAt,
                    CancellationTable.reason,
                ).where { filterClause }
                .orderBy(CancellationTable.createdAt to SortOrder.DESC)
                .toList()

        val names = operatorHrNames(rows.map { it[EmployeeTable.id].value }.toSet(), hrNames)
        val items =
            rows.map { row ->
                val operator = TerminalEmployee.displayName(row[EmployeeTable.username])
                StatisticsDto.CanceledItemDto(
                    itemName = row[ProductTable.name],
                    unitPrice = row[OrderItemTable.unitPrice].toDecimalString(),
                    quantity = 1,
                    totalAmount = row[OrderItemTable.finalPrice].toDecimalString(),
                    billNumber = row.getOrNull(BillTable.billNumber) ?: "-",
                    employeeName = operator,
                    time = row[CancellationTable.createdAt].toString(),
                    reason = row[CancellationTable.reason] ?: "",
                    displayName = names[row[EmployeeTable.id].value] ?: operator,
                )
            }

        val totalAmount = rows.sumOf { it[OrderItemTable.finalPrice] }.toDecimalString()

        return StatisticsDto.CanceledItemsResponseDto(
            canceledItems = items,
            totalAmount = totalAmount,
            totalCount = items.size,
        )
    }

    // =========================== OPERATOR NAMES ===========================

    /**
     * The HR first + last name of the operators in [employeeIds], in one query, when [hrNames] says
     * the caller may see them (`EmployeeHrNames.visibleTo`); otherwise empty, so every caller falls
     * back to the username label. The one place the statistics rollups decide this.
     */
    private fun operatorHrNames(
        employeeIds: Collection<UUID>,
        hrNames: Boolean,
    ): Map<UUID, String> = if (hrNames) EmployeeHrNames.byEmployeeId(employeeIds) else emptyMap()

    // =========================== PAGINATION HELPERS ===========================

    /**
     * Builds [ApiResponseDto.PaginationInfo] from page parameters and total item count.
     */
    private fun buildPaginationInfo(
        page: Int,
        pageSize: Int,
        totalItems: Long,
    ): ApiResponseDto.PaginationInfo {
        val totalPages = if (totalItems == 0L) 0 else ceil(totalItems.toDouble() / pageSize).toInt()
        return ApiResponseDto.PaginationInfo(
            currentPage = page,
            pageSize = pageSize,
            totalItems = totalItems,
            totalPages = totalPages,
            hasNext = page < totalPages - 1,
            hasPrevious = page > 0,
        )
    }

    /**
     * Order-by terms for a bill list, most significant first. Callers must have [leftJoinZBon]'d,
     * for the same reason the filter clause requires it.
     *
     * RECENT/OLDEST sort on the **attributed** time — the Z-Bon close the filter also uses — so the
     * sort key and the filter key agree. Sorting on the bill's own `endTime` while filtering on the
     * Z-Bon close made a bill carried over from the previous evening appear at the far end of the
     * list it had just been attributed into.
     *
     * Every bill in one Z-Bon shares that Z-Bon's close timestamp, so attributed time alone would
     * tie a whole trading day and leave its order arbitrary. The second term restores real
     * chronological order *within* the day; the third is a unique tiebreaker, without which rows
     * with equal keys can shuffle between page queries and be duplicated or skipped across an
     * OFFSET boundary.
     *
     * HIGHEST/LOWEST sort on amount, which is independent of the day, so they take no time term —
     * just the same unique tiebreaker.
     */
    private fun sortToOrderBy(sort: StatisticsDto.StatisticsSortOption): Array<Pair<Expression<*>, SortOrder>> =
        when (sort) {
            StatisticsDto.StatisticsSortOption.RECENT ->
                arrayOf(
                    attributedTimeExpr() to SortOrder.DESC,
                    BillTable.endTime to SortOrder.DESC,
                    BillTable.id to SortOrder.DESC,
                )
            StatisticsDto.StatisticsSortOption.OLDEST ->
                arrayOf(
                    attributedTimeExpr() to SortOrder.ASC,
                    BillTable.endTime to SortOrder.ASC,
                    BillTable.id to SortOrder.ASC,
                )
            StatisticsDto.StatisticsSortOption.HIGHEST ->
                arrayOf(BillTable.paidAmount to SortOrder.DESC, BillTable.id to SortOrder.DESC)
            StatisticsDto.StatisticsSortOption.LOWEST ->
                arrayOf(BillTable.paidAmount to SortOrder.ASC, BillTable.id to SortOrder.ASC)
        }

    override fun getSalesTrends(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        currency: String,
        employee: Employee?,
        trainingOnly: Boolean,
    ): StatisticsDto.SalesTrendsResponseDto {
        // Copied verbatim from getSalesOverviewSummary. The two endpoints feed the same screen —
        // one headline total above a chart of its own parts — so any divergence here produces two
        // numbers that contradict each other with no way for the reader to tell which is right.
        // That includes the Z-Bon attribution: bills are booked by their Z-Bon's close (endTime),
        // unassigned bills fall back to their own endTime, via a LEFT JOIN + COALESCE. See there
        // for the full rationale.
        //
        // The COALESCE is grouped by (attributedTime.date() below), so it must render identically
        // in the SELECT and the GROUP BY. Both branches are plain table columns — no literal or
        // bound parameter — so Postgres always sees the same expression in both places.
        val attributedTime = attributedTimeExpr()
        val source =
            BillTable.join(
                ZBonTable,
                JoinType.LEFT,
                onColumn = BillTable.zBonId,
                otherColumn = ZBonTable.id,
            )

        var baseBillClause: Op<Boolean> =
            attributedTime.greater(startDate) and
                attributedTime.lessEq(endDate) and TrainingFilter.billFilter(trainingOnly)
        employee?.let {
            baseBillClause = baseBillClause and BillTable.employeeId.eq(employee.id)
        }

        // Storno bills, cancelled originals and receivable clearings are not revenue (revenueBillOp).
        val revenueBillClause = baseBillClause and revenueBillOp()

        // Daily revenue and transaction counts in one grouped pass.
        //
        // growthRate ("second half vs first") and bestPerformingDays both read this map in
        // insertion order, so a non-chronological series silently reports a made-up trend. A
        // GROUP BY returns rows in whatever order the planner likes, hence the orderBy — and the
        // Kotlin sort on top of it, because H2 (tests) happens to return grouped rows ascending
        // while Postgres (production) may hash-aggregate and reorder. That divergence means no
        // test on this harness can catch the DB-order assumption breaking; sorting here removes
        // the assumption instead of trusting it. ISO day keys sort lexicographically = chronologically.
        val dayExpr = attributedTime.date()
        val dayRevenueExpr = BillTable.billAmount.sum()
        // Sale bills per day, not rows.
        val dayCountExpr = saleBillCountExpr()
        val dailyRows =
            source
                .select(dayExpr, dayRevenueExpr, dayCountExpr)
                .where { revenueBillClause }
                .groupBy(dayExpr)
                .orderBy(dayExpr to SortOrder.ASC)
                .map { row ->
                    Triple(
                        row[dayExpr].toString(),
                        (row[dayRevenueExpr] ?: BigDecimal.ZERO).toDecimalString(),
                        row[dayCountExpr] ?: 0,
                    )
                }.sortedBy { it.first }
        val dailyRevenue = LinkedHashMap<String, String>()
        val dailyCounts = LinkedHashMap<String, Int>()
        dailyRows.forEach { (day, revenue, count) ->
            dailyRevenue[day] = revenue
            dailyCounts[day] = count
        }

        // Hourly revenue. Seed all 24 buckets FIRST: a missing key makes the chart compress its
        // axis and invent a busier day than actually happened.
        // clock time, not fiscal: a 23:30 bill is hour 23 regardless of its Z-Bon. Only the window
        // filter is attributed (hence `source`), never the bucket key.
        val hourExpr = BillTable.startTime.hour()
        val hourRevenueExpr = BillTable.billAmount.sum()
        val hourly = (0..23).associateWithTo(LinkedHashMap()) { BigDecimal.ZERO }
        source
            .select(hourExpr, hourRevenueExpr)
            .where { revenueBillClause }
            .groupBy(hourExpr)
            .forEach { row ->
                hourly[row[hourExpr]] = row[hourRevenueExpr] ?: BigDecimal.ZERO
            }
        val hourlyRevenue = hourly.mapValues { it.value.toDecimalString() }

        // Weekday pattern, summed from dailyRevenue so it can never disagree with it.
        // DayOfWeek.name yields "MONDAY"; the chart looks up exactly that casing.
        val weeklyPattern =
            dailyRevenue.entries
                .groupBy { LocalDate.parse(it.key).dayOfWeek.name }
                .mapValues { (_, days) ->
                    days.fold(BigDecimal.ZERO) { acc, e -> acc.add(BigDecimal(e.value)) }.toDecimalString()
                }

        val bestPerformingDays =
            dailyRevenue.entries
                .sortedByDescending { BigDecimal(it.value) }
                .take(3)
                .map { it.key }

        // Second half of the window vs the first. Stated explicitly because "growth rate" could
        // equally mean vs the previous period, which PeriodComparisonDto covers instead.
        val ordered = dailyRevenue.values.map { BigDecimal(it) }
        val mid = ordered.size / 2
        val growthRate =
            if (ordered.size < 2) {
                "0.00"
            } else {
                deltaPercent(
                    current = ordered.drop(mid).fold(BigDecimal.ZERO) { acc, v -> acc.add(v) },
                    previous = ordered.take(mid).fold(BigDecimal.ZERO) { acc, v -> acc.add(v) },
                ) ?: "0.00"
            }

        return StatisticsDto.SalesTrendsResponseDto(
            startDate = startDate.toString(),
            endDate = endDate.toString(),
            dailyRevenue = dailyRevenue,
            dailyTransactionCounts = dailyCounts,
            hourlyRevenue = hourlyRevenue,
            growthRate = growthRate,
            bestPerformingDays = bestPerformingDays,
            weeklyPattern = weeklyPattern,
            currency = currency,
        )
    }

    override fun getHourlySalesDistribution(date: LocalDate): Map<Int, String> {
        // Revenue bills only (revenueBillOp), like every other revenue figure in this file: a Zahlartwechsel
        // across an hour boundary otherwise left the cancelled original in one hour and its Storno
        // and Neubuchung in the next — a false spike beside a false dip. Summed in BigDecimal;
        // the old Double round-trip could drop a cent.
        val hourlyDistribution =
            BillTable
                .innerJoin(PaymentTable, { BillTable.id }, { PaymentTable.billId })
                .select(BillTable.startTime.hour(), PaymentTable.amount.sum())
                .where { BillTable.startTime.date().eq(date) and TrainingFilter.billNotTraining() and revenueBillOp() }
                .groupBy(BillTable.startTime.hour())
                .associate { row ->
                    row[BillTable.startTime.hour()] to (row[PaymentTable.amount.sum()] ?: BigDecimal.ZERO).toDecimalString()
                }

        return hourlyDistribution
    }

    // =========================== PRODUCT STATISTICS ===========================

    override fun getBestSellers(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        currency: String,
        limit: Int,
        trainingOnly: Boolean,
    ): StatisticsDto.BestSellersResponseDto {
        // Attributed by the Z-Bon that booked the bill, exactly as the financial overview and the
        // sales trends are: a bill rung at 23:30 whose Z-Bon closes the next day is the closing
        // day's business, and its product lines must move with its money. A bill with no Z-Bon yet
        // falls back to its own endTime (catalogLineFilter → buildBillFilterClause).
        //
        // Active bills only (activeBillOp): a cancelled original and its Storno both drop out, so a
        // Zahlartwechsel's items count once — on the Neubuchung. Without it a stornoed sale's
        // products were counted twice, the Katalog showing 28 Cola for 15 standing (admin16 FAIL-4).
        val whereClause = catalogLineFilter(startDate, endDate, trainingOnly)
        val lineRevenue = OrderItemTable.finalPrice.sum()
        val itemPromotions = itemPromotionDeductionsBy(ProductTable.id, whereClause)

        // Every product sold in the window, revenue net of its item promotions (see
        // getCategoryPerformance for why). Ranked in memory because the net figure is the sum of two
        // queries; the product catalogue is small enough that this is cheaper than a correlated subquery.
        val products =
            catalogLineSource()
                .select(
                    ProductTable.id,
                    ProductTable.name,
                    ProductCategoryTable.name,
                    OrderItemTable.id.count(),
                    lineRevenue,
                ).where { whereClause }
                .groupBy(ProductTable.id, ProductTable.name, ProductCategoryTable.name)
                .map { row ->
                    val net = (row[lineRevenue] ?: BigDecimal.ZERO) - (itemPromotions[row[ProductTable.id]] ?: BigDecimal.ZERO)
                    StatisticsDto.ProductPerformanceDto(
                        productId = row[ProductTable.id].value,
                        productName = row[ProductTable.name],
                        categoryName = row[ProductCategoryTable.name],
                        quantitySold = row[OrderItemTable.id.count()],
                        revenue = net.toDecimalString(),
                        salesPercentage = null,
                        growthRate = null, // set by StatisticsServiceImpl when compare is on
                    )
                }

        return StatisticsDto.BestSellersResponseDto(
            period = "$startDate to $endDate",
            topProductsByQuantity = products.sortedByDescending { it.quantitySold }.take(limit),
            topProductsByRevenue = products.sortedByDescending { BigDecimal(it.revenue) }.take(limit),
            fastestGrowingProducts = emptyList(), // no growth analysis: the compare flag fills growthRate instead
            uniqueProductsSold = products.size,
        )
    }

    override fun getCategoryPerformance(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        currency: String,
        trainingOnly: Boolean,
    ): StatisticsDto.CategoryPerformanceResponseDto {
        // Same bill set and attribution as getBestSellers — the categories regroup exactly the
        // product lines shown beside them.
        //
        // The category revenue is what the lines were CHARGED, so the column reconciles with the
        // Umsatz tile (admin17 NOTE-11: Σ list prices 279,90 + extras 4,00 against Umsatz 258,52):
        //   finalPrice  = (unitPrice + extras) × manual discount — what the bill's billAmount sums
        //   − the item promotions granted on the line (bill_promotion rows tied to an order item)
        // A bill-wide promotion belongs to no line — the server apportions it per VAT rate, never per
        // item — and an owner tip belongs to no product, so both are returned once as reconciliation
        // figures. Σ categories − billWidePromotions + ownerTips = Σ(billAmount − promotionAmount) +
        // owner tips, the overview's Umsatz over the same active bills.
        val whereClause = catalogLineFilter(startDate, endDate, trainingOnly)
        val lineRevenue = OrderItemTable.finalPrice.sum()
        val itemPromotions = itemPromotionDeductionsBy(ProductCategoryTable.id, whereClause)

        val categoryMetrics =
            catalogLineSource()
                .select(
                    ProductCategoryTable.id,
                    ProductCategoryTable.name,
                    ProductTable.id.countDistinct(),
                    OrderItemTable.id.count(),
                    lineRevenue,
                    ProductTable.price.avg(),
                ).where { whereClause }
                .groupBy(ProductCategoryTable.id, ProductCategoryTable.name)
                .map { row ->
                    val net =
                        (row[lineRevenue] ?: BigDecimal.ZERO) -
                            (itemPromotions[row[ProductCategoryTable.id]] ?: BigDecimal.ZERO)
                    StatisticsDto.CategoryMetricsDto(
                        categoryId = row[ProductCategoryTable.id].value,
                        categoryName = row[ProductCategoryTable.name],
                        productCount = row[ProductTable.id.countDistinct()].toInt(),
                        totalQuantitySold = row[OrderItemTable.id.count()],
                        totalRevenue = net.toDecimalString(),
                        averagePrice = (row[ProductTable.price.avg()] ?: BigDecimal.ZERO).toPlainString(),
                        growthRate = null, // set by StatisticsServiceImpl when compare is on
                    )
                }

        val reconciliation =
            catalogReconciliation(
                billFilter = whereClause,
                itemPromotionTotal = itemPromotions.values.fold(BigDecimal.ZERO, BigDecimal::add),
            )
        val categoryTotal = categoryMetrics.fold(BigDecimal.ZERO) { acc, c -> acc + BigDecimal(c.totalRevenue) }

        val mostProfitableCategory =
            categoryMetrics.maxByOrNull { BigDecimal(it.totalRevenue) }?.categoryName ?: ""
        val fastestGrowingCategory = "" // not computed

        val categoryRevenueDistribution =
            categoryMetrics.associate {
                it.categoryName to it.totalRevenue
            }

        return StatisticsDto.CategoryPerformanceResponseDto(
            period = "$startDate to $endDate",
            categoryMetrics = categoryMetrics,
            mostProfitableCategory = mostProfitableCategory,
            fastestGrowingCategory = fastestGrowingCategory,
            categoryRevenueDistribution = categoryRevenueDistribution,
            billWidePromotions = reconciliation.billWidePromotions.toDecimalString(),
            ownerTips = reconciliation.ownerTips.toDecimalString(),
            reconciledRevenue =
                (categoryTotal - reconciliation.billWidePromotions + reconciliation.ownerTips).toDecimalString(),
        )
    }

    // The order-item lines the Katalog reads, joined to their product, category and bill, and
    // LEFT-joined to the bill's Z-Bon for the attribution in catalogLineFilter.
    private fun catalogLineSource(): Join =
        OrderItemTable
            .innerJoin(ProductTable, { OrderItemTable.productId }, { ProductTable.id })
            .innerJoin(ProductCategoryTable, { ProductTable.categoryId }, { ProductCategoryTable.id })
            .innerJoin(BillTable, { OrderItemTable.billId }, { BillTable.id })
            .leftJoinZBon()

    // THE Katalog bill filter: Z-Bon-attributed window, training filter, revenue bills only (no
    // receivable clearing — it carries no line, but its reconciliation figures must match the Umsatz).
    private fun catalogLineFilter(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        trainingOnly: Boolean,
    ): Op<Boolean> = buildBillFilterClause(startDate, endDate, null, trainingOnly, null) and revenueBillOp()

    // Σ of the ITEM promotions (bill_promotion rows tied to an order item) on the Katalog's lines,
    // per [key]. Joined through the order item, so a deduction books to the category and product of
    // the line it was granted on, and to that line's bill — the same rows catalogLineSource reads.
    private fun <K> itemPromotionDeductionsBy(
        key: Column<K>,
        whereClause: Op<Boolean>,
    ): Map<K, BigDecimal> {
        val deducted = BillPromotionTable.deductedAmount.sum()
        return BillPromotionTable
            .innerJoin(OrderItemTable, { BillPromotionTable.orderItemId }, { OrderItemTable.id })
            .innerJoin(ProductTable, { OrderItemTable.productId }, { ProductTable.id })
            .innerJoin(ProductCategoryTable, { ProductTable.categoryId }, { ProductCategoryTable.id })
            .innerJoin(BillTable, { OrderItemTable.billId }, { BillTable.id })
            .leftJoinZBon()
            .select(key, deducted)
            .where { whereClause }
            .groupBy(key)
            .associate { row -> row[key] to (row[deducted] ?: BigDecimal.ZERO) }
    }

    // The two Katalog reconciliation figures over the bills matched by [billFilter]: the promotions
    // that are not on a line (Σ promotionAmount − [itemPromotionTotal]), and the owner tips — every
    // tip not kept by an employee, the complement employeeTipSumExpr takes out of the overview.
    private fun catalogReconciliation(
        billFilter: Op<Boolean>,
        itemPromotionTotal: BigDecimal,
    ): CatalogReconciliation {
        val promotions = BillTable.promotionAmount.sum()
        val tips = BillTable.tips.sum()
        val employeeTips = employeeTipSumExpr()
        val row =
            BillTable
                .leftJoinZBon()
                .select(promotions, tips, employeeTips)
                .where { billFilter }
                .singleOrNull()
        val promotionTotal = row?.get(promotions) ?: BigDecimal.ZERO
        val tipTotal = row?.get(tips) ?: BigDecimal.ZERO
        val employeeTipTotal = row?.get(employeeTips) ?: BigDecimal.ZERO
        return CatalogReconciliation(
            billWidePromotions = promotionTotal - itemPromotionTotal,
            ownerTips = tipTotal - employeeTipTotal,
        )
    }

    override fun getExtrasBreakdown(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        currency: String,
        trainingOnly: Boolean,
    ): List<StatisticsDto.ExtraOptionStatDto> {
        // Σ(priceDeltaSnapshot × quantity) per option. See NOTE below if the times/sum expression
        // does not type-check under this Exposed version.
        val addedRevenue =
            (
                ItemExtrasTable.priceDeltaSnapshot times
                    ItemExtrasTable.quantity.castTo<BigDecimal>(DecimalColumnType(19, 4))
            ).sum()
        val timesChosen = ItemExtrasTable.quantity.sum()

        // Extras ride on their order item's bill, so they book to the same Z-Bon day the product
        // line and the money do. See getBestSellers for the full rationale.
        val attributedTime = attributedTimeExpr()

        // Legacy rows (created before the choice-group snapshot system) have optionNameSnapshot = ""
        // and groupNameSnapshot = null, but still carry product_extras_id — so fall back to the live
        // ProductExtras.name for the display name (LEFT join keeps rows whose extra was later deleted).
        // Grouping additionally by the live name can split one option into a legacy row (empty
        // snapshot) and a snapshotted row; they are merged by display name in-memory below so the
        // section shows one row per option with the full, correct revenue.
        val merged = LinkedHashMap<Pair<String, String?>, Pair<Long, BigDecimal>>()
        ItemExtrasTable
            .innerJoin(OrderItemTable, { ItemExtrasTable.orderItemId }, { OrderItemTable.id })
            .innerJoin(BillTable, { OrderItemTable.billId }, { BillTable.id })
            .join(ProductExtrasTable, JoinType.LEFT, onColumn = ItemExtrasTable.productExtrasId, otherColumn = ProductExtrasTable.id)
            .join(ZBonTable, JoinType.LEFT, onColumn = BillTable.zBonId, otherColumn = ZBonTable.id)
            .select(
                ItemExtrasTable.optionNameSnapshot,
                ItemExtrasTable.groupNameSnapshot,
                ProductExtrasTable.name,
                timesChosen,
                addedRevenue,
            ).where {
                // Active bills only — extras ride on the same item lines as getBestSellers.
                attributedTime.greater(startDate) and
                    attributedTime.lessEq(endDate) and TrainingFilter.billFilter(trainingOnly) and
                    activeBillOp()
            }.groupBy(ItemExtrasTable.optionNameSnapshot, ItemExtrasTable.groupNameSnapshot, ProductExtrasTable.name)
            .forEach { row ->
                val displayName =
                    row[ItemExtrasTable.optionNameSnapshot]
                        .ifBlank { row.getOrNull(ProductExtrasTable.name) ?: "—" }
                val key = displayName to row[ItemExtrasTable.groupNameSnapshot]
                val (times0, revenue0) = merged[key] ?: (0L to BigDecimal.ZERO)
                merged[key] = (times0 + (row[timesChosen] ?: 0L).toLong()) to
                    (revenue0 + (row[addedRevenue] ?: BigDecimal.ZERO))
            }

        return merged.entries
            .sortedByDescending { it.value.second }
            .map { (key, agg) ->
                StatisticsDto.ExtraOptionStatDto(
                    optionName = key.first,
                    groupName = key.second,
                    timesChosen = agg.first,
                    addedRevenue = agg.second.toPlainString(),
                )
            }
    }

    override fun getPromotionBreakdown(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        currency: String,
        trainingOnly: Boolean,
    ): List<StatisticsDto.PromotionStatDto> {
        val timesApplied = BillPromotionTable.id.count()
        val totalDeducted = BillPromotionTable.deductedAmount.sum()

        // A discount is booked with the bill it was granted on, so it follows that bill's Z-Bon —
        // not `appliedAt` and not the bill's own clock time. See getBestSellers for the rationale.
        val attributedTime = attributedTimeExpr()

        return BillPromotionTable
            .innerJoin(PromotionTable, { BillPromotionTable.promotionId }, { PromotionTable.id })
            .innerJoin(BillTable, { BillPromotionTable.billId }, { BillTable.id })
            .join(ProductTable, JoinType.LEFT, onColumn = PromotionTable.targetProductId, otherColumn = ProductTable.id)
            .join(ZBonTable, JoinType.LEFT, onColumn = BillTable.zBonId, otherColumn = ZBonTable.id)
            .select(
                PromotionTable.id,
                PromotionTable.name,
                PromotionTable.discountType,
                ProductTable.name,
                timesApplied,
                totalDeducted,
            ).where {
                // Active bills only — the Z-Bon's promotion summary rule (Σ over activeBills) and
                // the overview's totalDiscounts. A stornoed bill's promotions were granted on a sale
                // that no longer stands; counting them made the Aktionen table 31,88 € against the
                // Z-Bon's 29,38 € (admin16 FAIL-3).
                attributedTime.greater(startDate) and
                    attributedTime.lessEq(endDate) and TrainingFilter.billFilter(trainingOnly) and
                    activeBillOp()
            }.groupBy(PromotionTable.id, PromotionTable.name, PromotionTable.discountType, ProductTable.name)
            .orderBy(totalDeducted, SortOrder.DESC)
            .map { row ->
                StatisticsDto.PromotionStatDto(
                    promotionId = row[PromotionTable.id].value,
                    name =
                        row[ProductTable.name]
                            ?.let { "${row[PromotionTable.name]} ($it)" }
                            ?: row[PromotionTable.name],
                    discountType = row[PromotionTable.discountType].name,
                    timesApplied = row[timesApplied],
                    totalDeducted = (row[totalDeducted] ?: BigDecimal.ZERO).toPlainString(),
                )
            }
    }

    override fun getProductPerformance(
        productId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
    ): StatisticsDto.ProductPerformanceDto {
        val result =
            OrderItemTable
                .innerJoin(ProductTable, { OrderItemTable.productId }, { ProductTable.id })
                .innerJoin(ProductCategoryTable, { ProductTable.categoryId }, { ProductCategoryTable.id })
                .innerJoin(BillTable, { OrderItemTable.billId }, { BillTable.id })
                .select(
                    ProductTable.id,
                    ProductTable.name,
                    ProductCategoryTable.name,
                    OrderItemTable.id.count(),
                    OrderItemTable.unitPrice.sum(),
                ).where {
                    ProductTable.id.eq(productId) and
                        BillTable.startTime.date().greaterEq(startDate) and
                        BillTable.startTime.date().lessEq(endDate) and
                        TrainingFilter.billNotTraining() and
                        activeBillOp()
                }.groupBy(ProductTable.id, ProductTable.name, ProductCategoryTable.name)
                .singleOrNull()

        if (result == null) {
            throw IllegalArgumentException("Product not found: $productId")
        }

        return StatisticsDto.ProductPerformanceDto(
            productId = result[ProductTable.id].value,
            productName = result[ProductTable.name],
            categoryName = result[ProductCategoryTable.name],
            quantitySold = result[OrderItemTable.id.count()],
            revenue = (result[OrderItemTable.unitPrice.sum()]?.toDouble() ?: 0.0).toDecimalString(),
            salesPercentage = null,
            growthRate = null, // set by StatisticsServiceImpl when compare is on
        )
    }

    // =========================== FINANCIAL STATISTICS ===========================

    override fun getFinancialOverview(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        currency: String,
        employee: Employee?,
        trainingOnly: Boolean,
    ): StatisticsDto.FinancialOverviewResponseDto {
        // Bills attributed by their Z-Bon close, falling back to the bill's own endTime.
        //
        // This used to fall back to `dateTimeLiteral(currentTimeStamp())`, and that was a live defect:
        // the literal is rendered when the query is BUILT, so a bill with no Z-Bon was attributed to
        // "now" rather than to when it was actually settled. A window whose endDate the client captured
        // microseconds earlier therefore excluded it, giving a flaky, data-dependent under-count. The
        // same fallback caused the round-6 regression that made 7e764dc82 strip attribution from the
        // catalog queries entirely; 2fa993e1e fixed it behind attributedTimeExpr() for trends and this
        // overview's siblings, and df2761f7c restored catalog and promotions. This was the last copy.
        //
        // The dateTimeParam caution that used to live here does not apply: attributedTimeExpr() is two
        // columns, not a literal, and it appears only in this subquery's WHERE, never beside a GROUP BY.
        val attributedTime = attributedTimeExpr()
        val windowBillIds =
            BillTable
                .join(ZBonTable, JoinType.LEFT, onColumn = BillTable.zBonId, otherColumn = ZBonTable.id)
                .select(BillTable.id)
                .where { attributedTime.greater(startDate) and attributedTime.lessEq(endDate) }

        val baseQuery =
            BillTable
                .innerJoin(PaymentTable, { BillTable.id }, { PaymentTable.billId })
                .innerJoin(PaymentTypeTable, { PaymentTable.paymentTypeId }, { PaymentTypeTable.id })

        // Active bills only (activeBillOp): exclude storno (reverse) rows and cancelled originals so
        // gross/net/VAT/payment/daily totals reflect real revenue. Storno + cancellation accounting is
        // surfaced separately via totalRefunds below.
        var whereClause: Op<Boolean> =
            BillTable.id.inSubQuery(windowBillIds) and
                TrainingFilter.billFilter(trainingOnly) and
                activeBillOp()
        employee?.let {
            whereClause = whereClause and BillTable.employeeId.eq(it.id)
        }
        // The revenue figures (gross/net, the daily series and their Ø Bon) count the revenue bills
        // only (revenueBillOp): a receivable clearing collects money for a sale booked when its invoice
        // was issued, and the Z-Bon rollup shown beside the KPI on the Monatsabschluss leaves it out of
        // `totalPosRevenue`. The takings figures (payment breakdown, cash sales) keep it on
        // `whereClause` — the money came in, as the Z-Bon's own Zahlungsarten keep it.
        val revenueClause: Op<Boolean> = whereClause and BillTable.clearsSalesInvoiceId.isNull()
        // ONE bill set for every figure below. The VAT, daily-VAT, refund and tip queries used to omit
        // the employee filter, so an employee-scoped overview mixed that person's gross with the shop's
        // VAT — a negative net revenue (E2E-2026-09-18 #9).
        val employeeFilter: Op<Boolean> = employee?.let { BillTable.employeeId eq it.id } ?: Op.TRUE

        // Σ of the employee tips inside the takings, over exactly the bill set `whereClause` selects.
        //
        // Deliberately its OWN BillTable-only aggregate, and deliberately NOT
        // `revenueExcludingEmployeeTips` (which reads `paidAmount`, the wrong column here):
        // `baseQuery` fans out to one row PER PAYMENT (BillTable ⨝ PaymentTable ⨝ PaymentTypeTable).
        // `BillTable.tips` is a BILL column, so summing the CASE across that join multiplies the tip
        // by the bill's payment count — a cash+card split would subtract a 7.60 tip twice, turning
        // one defect into another. Aggregating over bare BillTable counts each bill once. This is
        // the shape `totalRefunds` and `totalTips` below already use, and it is valid because
        // `whereClause` names only BillTable columns plus the self-contained windowBillIds subquery.
        val employeeTipSum = employeeTipSumExpr()
        val employeeTips =
            BillTable
                .select(employeeTipSum)
                .where { revenueClause }
                .singleOrNull()
                ?.get(employeeTipSum) ?: BigDecimal.ZERO

        // Gross revenue: the period's takings less the employee tips inside them.
        //
        // Revenue, not takings — the same call the four `revenueExcludingEmployeeTips` sites make,
        // reached here through a different column (`PaymentTable.amount`), which is the only reason
        // P1.4 did not already correct it. An EMPLOYEE tip (TrinkgeldAN) is non-taxable and is not
        // turnover, so a figure rendered as "Bruttoumsatz" must not contain it: the Z-Bon rollup
        // shown BESIDE this KPI on the Monatsabschluss already excludes it (`ZBonServiceImpl`:
        // `totalPosRevenue = Σ(billAmount − promotionAmount) + Σ tips of ADMIN closers`), and the
        // two figures are meant to reconcile. `netRevenue` below inherits the correction for free:
        // post-P1.1 an employee tip writes no BillVatDetail row, so an uncorrected tip would land
        // whole in net revenue and be reported as untaxed turnover.
        //
        // The takings figures in this same DTO keep the tip ON PURPOSE — paymentMethodBreakdown,
        // cashFlow.cashSales and totalRefunds answer "what came through the drawer", tip included.
        val grossRevenue =
            (
                baseQuery
                    .select(PaymentTable.amount.sum())
                    .where { revenueClause }
                    .singleOrNull()
                    ?.get(PaymentTable.amount.sum()) ?: BigDecimal.ZERO
            ) - employeeTips

        // The receivable clearings the payment breakdown below holds and grossRevenue does not.
        val clearedSum = BillTable.billAmount.sum()
        val receivableCleared =
            BillTable
                .select(clearedSum)
                .where { whereClause and BillTable.clearsSalesInvoiceId.isNotNull() }
                .singleOrNull()
                ?.get(clearedSum) ?: BigDecimal.ZERO

        // Calculate VAT breakdown using BillVatDetailTable
        val vatBreakdown =
            BillVatDetailTable
                .innerJoin(BillTable, { BillVatDetailTable.billId }, { BillTable.id })
                .innerJoin(VatTable, { BillVatDetailTable.vatId }, { VatTable.id })
                .select(
                    VatTable.vatClass,
                    VatTable.vatRate,
                    BillVatDetailTable.vatAmount.sum(),
                    BillVatDetailTable.netAmount.sum(),
                    BillVatDetailTable.totalAmount.sum(),
                    BillVatDetailTable.billId.countDistinct(),
                ).where {
                    BillTable.id.inSubQuery(windowBillIds) and
                        TrainingFilter.billFilter(trainingOnly) and
                        activeBillOp() and
                        employeeFilter
                }.groupBy(VatTable.vatClass, VatTable.vatRate)
                .associate { row ->
                    val vatClass = row[VatTable.vatClass]
                    val vatRate = row[VatTable.vatRate]
                    val vatAmount = row[BillVatDetailTable.vatAmount.sum()] ?: BigDecimal.ZERO
                    val netAmount = row[BillVatDetailTable.netAmount.sum()] ?: BigDecimal.ZERO
                    val totalAmount = row[BillVatDetailTable.totalAmount.sum()] ?: BigDecimal.ZERO
                    val transactionCount = row[BillVatDetailTable.billId.countDistinct()].toInt()

                    vatClass to
                        VatSummaryDto(
                            vatRate = (vatRate * BigDecimal(100)).toInt().toString(), // Convert to percentage
                            netAmount = netAmount.toDecimalString(),
                            vatAmount = vatAmount.toDecimalString(),
                            totalAmount = totalAmount.toDecimalString(),
                            transactionCount = transactionCount,
                        )
                }

        // Calculate net revenue (total minus VAT)
        val totalVatAmount = vatBreakdown.values.sumOf { it.vatAmount.toBigDecimal() }
        val netRevenue = grossRevenue - totalVatAmount

        // Payment method breakdown. The "Anteil" percentages are the share each method takes of the
        // total takings, apportioned with the largest-remainder method so they sum to EXACTLY 100.00 —
        // independent per-row 2-dp rounding otherwise drifts the mix to 99.99 / 100.01.
        val paymentRows =
            baseQuery
                .select(
                    PaymentTypeTable.name,
                    PaymentTypeTable.enName,
                    PaymentTypeTable.chName,
                    PaymentTable.amount.sum(),
                    PaymentTable.amount.count(),
                ).where { whereClause }
                // `name` is unique, so adding the two translation columns (display only) to the
                // GROUP BY cannot split or merge a group: the figures stay exactly per tender.
                .groupBy(PaymentTypeTable.name, PaymentTypeTable.enName, PaymentTypeTable.chName)
                .map { row ->
                    FinancialPaymentRow(
                        name = row[PaymentTypeTable.name],
                        enName = row[PaymentTypeTable.enName],
                        chName = row[PaymentTypeTable.chName],
                        totalAmount = row[PaymentTable.amount.sum()] ?: BigDecimal.ZERO,
                        transactionCount = row[PaymentTable.amount.count()].toInt(),
                    )
                }
        val paymentPercents = largestRemainderPercents(paymentRows.map { it.totalAmount })
        val paymentMethodBreakdown =
            paymentRows
                .mapIndexed { i, (paymentMethod, enName, chName, totalAmount, transactionCount) ->
                    paymentMethod to
                        PaymentMethodSummaryDto(
                            paymentMethod = paymentMethod,
                            paymentMethodEN = enName,
                            paymentMethodCH = chName,
                            totalAmount = totalAmount.toDecimalString(),
                            transactionCount = transactionCount,
                            revenuePercentage = paymentPercents[i].toDecimalString(),
                            averageTransactionValue =
                                (
                                    if (transactionCount >
                                        0
                                    ) {
                                        totalAmount / BigDecimal(transactionCount)
                                    } else {
                                        BigDecimal.ZERO
                                    }
                                ).toDecimalString(),
                        )
                }.toMap()

        // Cash takings in the window, by the tender's is_cash flag. It used to match the English names
        // "Cash"/"CASH"/"cash", so a shop whose cash tender is called BAR reported 0. This is takings,
        // not a drawer balance — the Monatsabschluss reads the fiscal cash book for that.
        val cashSales =
            baseQuery
                .select(PaymentTable.amount.sum())
                .where { whereClause and (PaymentTypeTable.isCash eq true) }
                .singleOrNull()
                ?.get(PaymentTable.amount.sum()) ?: BigDecimal.ZERO

        val cashFlow =
            StatisticsDto.CashFlowDto(
                openingBalance = "0.00", // Would need additional tracking table for opening balance
                cashSales = cashSales.toDecimalString(),
                cashRefunds = "0.00", // Would need refund tracking
                expenses = "0.00", // Would need expense tracking
                closingBalance = cashSales.toDecimalString(),
                netCashFlow = "0.00", // Simplified calculation
            )

        // The same employee-tip correction, per day. ONE grouped BillTable-only query rather than a
        // per-date round trip, and BillTable-only for the fan-out reason given at `employeeTips`.
        // The daily series has to net out the same tip the headline does: `MonthlyCloseServiceImpl`
        // renders it as the "Bruttoumsatz" KPI's own sparkline, and Σ(daily) must stay equal to the
        // headline. Both queries group by the same expression under the same clause, so their date
        // buckets line up exactly.
        val employeeTipsByDate =
            BillTable
                .select(BillTable.startTime.date(), employeeTipSum)
                .where { revenueClause }
                .groupBy(BillTable.startTime.date())
                .associate { row ->
                    row[BillTable.startTime.date()] to (row[employeeTipSum] ?: BigDecimal.ZERO)
                }

        // Sale bills per day — the daily transactionCount and the divisor of its Ø Bon. BillTable-only
        // for the same fan-out reason as `employeeTips`: `BillTable.id.count()` over `baseQuery`
        // counted PAYMENT rows, so a split payment was two transactions.
        val saleBillCount = saleBillCountExpr()
        val saleBillsByDate =
            BillTable
                .select(BillTable.startTime.date(), saleBillCount)
                .where { revenueClause }
                .groupBy(BillTable.startTime.date())
                .associate { row ->
                    row[BillTable.startTime.date()] to (row[saleBillCount] ?: 0)
                }

        // Calculate daily financial summary
        val dailyFinancialSummary =
            baseQuery
                .select(
                    BillTable.startTime.date(),
                    PaymentTable.amount.sum(),
                ).where { revenueClause }
                .groupBy(BillTable.startTime.date())
                .associate { row ->
                    val date = row[BillTable.startTime.date()].toString()
                    val dailyGrossRevenue =
                        (row[PaymentTable.amount.sum()] ?: BigDecimal.ZERO) -
                            (employeeTipsByDate[row[BillTable.startTime.date()]] ?: BigDecimal.ZERO)
                    val transactionCount = saleBillsByDate[row[BillTable.startTime.date()]] ?: 0

                    // Calculate daily VAT for this date
                    val dailyVatAmount =
                        BillVatDetailTable
                            .innerJoin(BillTable, { BillVatDetailTable.billId }, { BillTable.id })
                            .select(BillVatDetailTable.vatAmount.sum())
                            .where {
                                BillTable.startTime.date().eq(row[BillTable.startTime.date()]) and
                                    BillTable.id.inSubQuery(windowBillIds) and
                                    TrainingFilter.billFilter(trainingOnly) and
                                    activeBillOp() and
                                    employeeFilter
                            }.singleOrNull()
                            ?.get(BillVatDetailTable.vatAmount.sum()) ?: BigDecimal.ZERO

                    val dailyNetRevenue = dailyGrossRevenue - dailyVatAmount

                    date to
                        StatisticsDto.DailyFinancialDto(
                            date = date,
                            grossRevenue = dailyGrossRevenue.toDecimalString(),
                            netRevenue = dailyNetRevenue.toDecimalString(),
                            transactionCount = transactionCount,
                            vatAmount = dailyVatAmount.toDecimalString(),
                            averageTransactionValue =
                                (
                                    if (transactionCount >
                                        0
                                    ) {
                                        dailyGrossRevenue / BigDecimal(transactionCount)
                                    } else {
                                        BigDecimal.ZERO
                                    }
                                ).toDecimalString(),
                        )
                }

        // Calculate total refunds from storno/reverse bills (refundingStornoOp): a Zahlartwechsel
        // Storno refunds nothing — its sale is booked again at once.
        val baseBillDateFilter: Op<Boolean> =
            BillTable.id.inSubQuery(windowBillIds) and
                TrainingFilter.billFilter(trainingOnly) and
                employeeFilter
        val stornoBillFilter = baseBillDateFilter and refundingStornoOp()
        val refundAgg =
            BillTable
                .select(BillTable.paidAmount.sum())
                .where { stornoBillFilter }
                .singleOrNull()
        // Storno bills carry negative paidAmount; negate to display as positive refund total
        val totalRefunds = (refundAgg?.get(BillTable.paidAmount.sum()) ?: BigDecimal.ZERO).negate()

        // Calculate total tips from active (non-storno) bills
        val activeBillFilter = baseBillDateFilter and activeBillOp()
        val tipsAgg =
            BillTable
                .select(BillTable.tips.sum())
                .where { activeBillFilter }
                .singleOrNull()
        val totalTips = tipsAgg?.get(BillTable.tips.sum()) ?: BigDecimal.ZERO

        return StatisticsDto.FinancialOverviewResponseDto(
            period = "$startDate to $endDate",
            currency = currency,
            grossRevenue = grossRevenue.toDecimalString(),
            netRevenue = netRevenue.toDecimalString(),
            totalRefunds = totalRefunds.toDecimalString(),
            totalTips = totalTips.toDecimalString(),
            vatBreakdown = vatBreakdown,
            paymentMethodBreakdown = paymentMethodBreakdown,
            receivableCleared = receivableCleared.toDecimalString(),
            cashFlow = cashFlow,
            dailyFinancialSummary = dailyFinancialSummary,
        )
    }

    override fun getDataDateRange(): Pair<LocalDate, LocalDate> {
        val trainingFilter = TrainingFilter.billNotTraining()
        val minExpr = BillTable.startTime.min()
        val minDate: LocalDate? =
            BillTable
                .select(minExpr)
                .where { trainingFilter }
                .map { row -> row[minExpr] }
                .firstOrNull()
                ?.date

        val maxExpr = BillTable.startTime.max()
        val maxDate: LocalDate? =
            BillTable
                .select(maxExpr)
                .where { trainingFilter }
                .map { row -> row[maxExpr] }
                .firstOrNull()
                ?.date

        val currentDate = currentTimeStamp().date

        return Pair(minDate ?: currentDate, maxDate ?: currentDate)
    }

    fun LocalDate.startOfWeek(firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY): LocalDate {
        val diff = (dayOfWeek.isoDayNumber - firstDayOfWeek.isoDayNumber + 7) % 7
        return this.minus(diff, DateTimeUnit.DAY)
    }

    fun LocalDate.startOfMonth(): LocalDate = LocalDate(year, month, 1)
}

/**
 * 2-dp percentage shares of a whole that sum to EXACTLY 100.00, via the largest-remainder method.
 * Independent per-row 2-dp rounding drifts a payment mix to 99.99 / 100.01; this floors every share
 * to basis points, then hands the leftover basis points to the largest fractional remainders so the
 * total lands on 100.00 exactly. Empty input → empty; a non-positive total → all "0.00". Results are
 * 2-dp BigDecimals in input order.
 */
internal fun largestRemainderPercents(amounts: List<BigDecimal>): List<BigDecimal> {
    val zero2 = BigDecimal.ZERO.setScale(2)
    val total = amounts.fold(BigDecimal.ZERO) { acc, a -> acc + a }
    if (amounts.isEmpty() || total.signum() <= 0) return amounts.map { zero2 }
    // Work in basis points (out of 10000 == 2-dp percent).
    val exact =
        amounts.map {
            it.multiply(BigDecimal(10000)).divide(total, 6, java.math.RoundingMode.HALF_UP)
        }
    val bp = exact.map { it.setScale(0, java.math.RoundingMode.FLOOR).toInt() }.toMutableList()
    var leftover = 10000 - bp.sum()
    val byRemainder = exact.indices.sortedByDescending { exact[it].subtract(BigDecimal(bp[it])) }
    var i = 0
    while (leftover > 0 && byRemainder.isNotEmpty()) {
        bp[byRemainder[i % byRemainder.size]] += 1
        leftover--
        i++
    }
    return bp.map { BigDecimal(it).divide(BigDecimal(100), 2, java.math.RoundingMode.HALF_UP) }
}
