/**
 * What the drawer took in cash from a set of bills — the closing's own rule, shared with the
 * pre-closing drawer count.
 *
 * Cash sales reach the Kassenbuch only as the closing's CASH_SALE aggregate, so between two
 * closings the drawer holds the book balance PLUS these takings. `ZBonServiceImpl.createReport`
 * derives the aggregate's amount from exactly this; `KassenbuchServiceImpl.recordCashCount`
 * derives the expected drawer content from exactly this. One definition, so a count taken
 * minutes before the closing can never disagree with the closing about what the drawer should
 * hold — two hand-written copies would drift the first time the derivation changes, as it did
 * once already when cash clearings (4c-2c) joined it. Found on the 2026-09-11 upgrade rehearsal:
 * the count compared against the bare book and signed the day's takings as a phantom
 * CASH_SURPLUS, which the closing's Geldtransit then carried to the safe on top of the takings.
 *
 * Every function here requires an ambient Exposed transaction.
 */
package org.example.project.features.zBon.domain

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.database.tables.BillVatDetailTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.zBon.database.tables.ZBonTable
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.utils.NonTaxableTipCarrier
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import java.math.BigDecimal

object DrawerCashDerivation {
    /** For callers that read only [Takings.netCashAmount], which does not depend on the opening tender. */
    private val NO_TENDER_LOOKUP: (Bill) -> Long? = { null }

    /** The bill-derived figures the aggregate CASH_SALE entry is built from. */
    data class Takings(
        /** Gross revenue over the active bills' VAT details — the aggregate's positive lines. */
        val revenueGross: BigDecimal,
        /**
         * Non-cash tender of SALE bills, per payment type — the aggregate's negative lines.
         * Clearing legs are excluded on purpose: a card clearing was never revenue, so it must
         * not be subtracted from the drawer (4c-2c).
         */
        val saleNonCashByType: Map<PaymentType, BigDecimal>,
        /**
         * Cash taken by clearing bills (Forderungsausgleich): not revenue, but in the drawer —
         * split by the **receivable tender that OPENED** each settled invoice.
         *
         * The key is deliberately the opening tender, never the cash the clearing itself brought
         * in: it is what lets the Kassenbuch line resolve its DATEV contra account through the same
         * `mapping.paymentTypes` cell the invoice's opening `NON_CASH_PAYMENT` leg booked through,
         * so settling a receivable clears exactly the account opening it debited. A cash tender has
         * no cell in that map at all, so keying on it would resolve nothing.
         *
         * A null key means the opening tender could not be determined (no linked original bill, or
         * an original whose receivable legs name more than one tender). Those keep today's
         * behaviour — a line with no `paymentTypeId`, which the export still refuses — rather than
         * guessing an account; see [receivableTenderOf].
         */
        val clearingCashByReceivableTender: Map<PaymentType?, BigDecimal>,
        /**
         * Non-taxable EMPLOYEE tips (TrinkgeldAN) of the SALE bills, which are NOT in
         * [revenueGross]: P1.1 stopped storing them as 0 % `BillVatDetail` rows because a tip is
         * not turnover. The money is still in the drawer, and the bill's payments still tender it,
         * so it must be added back or the closing understates the till by the tip.
         *
         * SALE bills only. A clearing bill's cash leg enters through [clearingCash], which sums the
         * whole cash payment — tip included — so adding it again there would double-count it.
         *
         * An OWNER tip (TrinkgeldAG) is taxed into the item rates and is therefore already inside
         * [revenueGross]; [org.example.project.utils.NonTaxableTipCarrier] returns ZERO for it, and
         * for a pre-P1.1 bill whose tip is still a 0 % row. That discrimination is exactly why this
         * routes through the carrier instead of reading `bill.tips` directly.
         */
        val nonTaxableTips: BigDecimal,
    ) {
        val totalNonCashWithdrawals: BigDecimal
            get() = saleNonCashByType.values.fold(BigDecimal.ZERO) { acc, v -> acc + v }

        /**
         * The whole day's clearing cash, whatever it was opened under. Derived from
         * [clearingCashByReceivableTender] so the per-tender split cannot disagree with the total
         * the drawer actually holds — [netCashAmount] is a function of THIS, which is why splitting
         * the clearing line per tender leaves the signed payload untouched.
         */
        val clearingCash: BigDecimal
            get() = clearingCashByReceivableTender.values.fold(BigDecimal.ZERO) { acc, v -> acc + v }

        /**
         * What the drawer gained: gross revenue less non-cash tender, plus cash clearings, plus the
         * non-taxable tips that are tendered but are not revenue.
         */
        val netCashAmount: BigDecimal
            get() = revenueGross - totalNonCashWithdrawals + clearingCash + nonTaxableTips
    }

    /** Bills not yet attributed to a closing, started after [since] — all of them when null. */
    fun unclosedBills(since: LocalDateTime?): List<Bill> =
        Bill
            .find {
                val unclosed = BillTable.zBonId eq null
                if (since == null) unclosed else unclosed and (BillTable.startTime greater since)
            }.toList()

    /**
     * The bills whose money is in the drawer: real (not training), not storno'd and not the
     * cancelled original.
     */
    fun activeBills(bills: List<Bill>): List<Bill> =
        bills
            .filter { !it.isTraining }
            .filter { it.deletingReason == null && it.deletedBill == null }

    /**
     * The [activeBills] that are turnover: every one but the receivable clearings. A clearing
     * (`clearsSalesInvoiceId` set) collects the money for an outgoing invoice issued earlier — the
     * turnover arose on the invoice's original bill — so it is in the drawer and the Zahlungsarten,
     * never in an Umsatz figure. THE server rule; `StatisticsRepositoryImpl.revenueBillOp` is its SQL
     * twin and the shared `BillActiveRevenue` its client twin.
     */
    fun saleBills(activeBills: List<Bill>): List<Bill> = activeBills.filter { it.clearsSalesInvoiceId == null }

    /** The [activeBills] that clear a receivable — the complement of [saleBills]. */
    fun clearingBills(activeBills: List<Bill>): List<Bill> = activeBills.filter { it.clearsSalesInvoiceId != null }

    /** One query for every bill's VAT details, rows ordered by id as the per-bill lazy load returned them. */
    fun vatDetailsByBill(bills: List<Bill>): Map<EntityID<Long>, List<BillVatDetail>> {
        val ids = bills.map { it.id }
        if (ids.isEmpty()) return emptyMap()
        return BillVatDetail
            .find { BillVatDetailTable.billId inList ids }
            .orderBy(BillVatDetailTable.id to SortOrder.ASC)
            .groupBy { it.bill.id }
    }

    /** One query for every bill's payments, rows ordered by id as the per-bill lazy load returned them. */
    fun paymentsByBill(bills: List<Bill>): Map<EntityID<Long>, List<Payment>> {
        val ids = bills.map { it.id }
        if (ids.isEmpty()) return emptyMap()
        return Payment
            .find { PaymentTable.billId inList ids }
            .orderBy(PaymentTable.id to SortOrder.ASC)
            .groupBy { it.bill.id }
    }

    /**
     * The derivation itself, over [activeBills] and their batch-fetched children.
     *
     * [originalBillIdOfClearing] names the ORIGINAL bill behind a clearing bill's invoice, or null —
     * the accounting answer `BillInvoiceLink.originalBillIdOfClearing`, passed in so this object
     * stays free of the invoice model. It only decides the KEYS of [Takings.clearingCashByReceivableTender].
     */
    fun derive(
        activeBills: List<Bill>,
        vatDetailsByBill: Map<EntityID<Long>, List<BillVatDetail>>,
        paymentsByBill: Map<EntityID<Long>, List<Payment>>,
        originalBillIdOfClearing: (Bill) -> Long?,
    ): Takings {
        // A clearing bill takes money for a sale ALREADY booked, so it is not revenue and the
        // drawer derivation cannot express it: split it out, keep only its cash leg.
        val clearingBills = clearingBills(activeBills)
        val saleBills = saleBills(activeBills)

        // Scope asymmetry, deliberate and value-identical today: `revenueGross` sums `activeBills`
        // while `nonTaxableTips` below sums `saleBills`. A clearing bill contributes nothing to
        // either — `SalesInvoiceServiceImpl` builds it with NO BillVatDetail rows and `tips = ZERO`
        // — so the wider scope here adds no revenue and the narrower scope below drops no tip.
        // `saleBills` is nonetheless the correct scope for the tip: a clearing bill's cash enters
        // through `clearingCash`, which sums the WHOLE cash payment, so a clearing tip counted here
        // too would be double-counted the day clearing bills start carrying one.
        val revenueGross = activeBills.flatMap { vatDetailsByBill[it.id].orEmpty() }.sumOf { it.totalAmount }
        // Reads `bill.vatDetails` lazily rather than the batch-fetched map above, because the
        // carrier is the SINGLE definition of "how much tip sits outside the VAT breakdown" and is
        // shared with signing and the DSFinV-K export. Its two guard clauses return before touching
        // the referrer, so the extra query is bounded by the number of EMPLOYEE-tipped bills.
        val nonTaxableTips = saleBills.sumOf { NonTaxableTipCarrier.outsideVatDetails(it) }
        val saleNonCashByType =
            saleBills
                .flatMap { paymentsByBill[it.id].orEmpty() }
                .filter { !it.paymentType.isCash }
                .groupBy { it.paymentType }
                .mapValues { (_, ps) -> ps.sumOf { it.amount } }
        // Grouped by the tender that OPENED each settled invoice, not flattened to one total: the
        // Kassenbuch line built from this needs a paymentTypeId to resolve its DATEV contra account
        // at all. Summing first and asking later is exactly what could not be undone — the
        // breakdown is gone by the time the line is built. The SUM is unchanged either way, which
        // is what keeps netCashAmount (and therefore the signed payload) byte-identical.
        val clearingCashByReceivableTender =
            clearingBills
                .mapNotNull { bill ->
                    val cash =
                        paymentsByBill[bill.id]
                            .orEmpty()
                            .filter { it.paymentType.isCash }
                    if (cash.isEmpty()) null else receivableTenderOf(bill, originalBillIdOfClearing) to cash.sumOf { it.amount }
                }.groupBy({ it.first }, { it.second })
                .mapValues { (_, amounts) -> amounts.fold(BigDecimal.ZERO) { acc, v -> acc + v } }
        return Takings(revenueGross, saleNonCashByType, clearingCashByReceivableTender, nonTaxableTips)
    }

    /**
     * The payment type that OPENED the receivable [clearingBill] settles — the `createsReceivable`
     * leg of the ORIGINAL sale, never the cash the clearing itself tendered.
     *
     * That is the account the settlement must clear: the original sale already booked this tender
     * as a `NON_CASH_PAYMENT` line through `mapping.paymentTypes`, so the clearing's contra is the
     * same cell. Routing it anywhere else double-counts (`income[*][*]` re-books revenue the sale
     * already booked) or asserts something untrue (`special[GELDTRANSIT]` claims the money came
     * from the bank, leaving the debtor open forever).
     *
     * Reads the original's completed payments — the same definition `SalesInvoiceServiceImpl` uses
     * to decide an invoice's receivable share.
     *
     * @return null when the invoice has no linked original bill, when that bill has no receivable
     *   leg, or when its receivable legs name MORE THAN ONE tender. The last case is left
     *   unresolved on purpose: splitting one clearing across several opening tenders has no ground
     *   truth in the data, and inventing a split would put a plausible wrong account in the ledger,
     *   where nothing downstream flags it. An unresolved line keeps today's behaviour — the export
     *   refuses it — which is recoverable; a wrong booking is not.
     */
    private fun receivableTenderOf(
        clearingBill: Bill,
        originalBillIdOfClearing: (Bill) -> Long?,
    ): PaymentType? {
        val originalBillId = originalBillIdOfClearing(clearingBill) ?: return null
        val original = Bill.findById(originalBillId) ?: return null
        return original.payments
            .filter { it.paymentStatus == PaymentStatus.COMPLETED }
            .filter { it.createsReceivable }
            .map { it.paymentType }
            .distinctBy { it.id }
            .singleOrNull()
    }

    /**
     * Cash the drawer has taken from bills not yet closed — what the next closing's CASH_SALE
     * aggregate will book. The period starts where the last closing ended, exactly as
     * `ZBonRepositoryImpl.createReport` starts the next report.
     *
     * Only [Takings.netCashAmount] is read, and it sums [Takings.clearingCashByReceivableTender]'s
     * VALUES whatever their keys, so the opening tender is not looked up here ([NO_TENDER_LOOKUP]):
     * the figure is the one the closing derives, without one invoice and one bill read per clearing.
     */
    fun unbookedCashTakings(): BigDecimal {
        val since =
            ZBon
                .all()
                .orderBy(ZBonTable.id to SortOrder.DESC)
                .firstOrNull()
                ?.endTime
        val active = activeBills(unclosedBills(since))
        return derive(active, vatDetailsByBill(active), paymentsByBill(active), NO_TENDER_LOOKUP).netCashAmount
    }
}
