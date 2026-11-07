package org.example.project.features.zBon.domain

import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.zBon.database.tables.ZBonTable
import org.example.project.features.zBon.domain.models.ZBon
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import java.math.BigDecimal

/**
 * The employee tips (TrinkgeldAN) of the still-open period, per employee — what the NEXT closing will
 * store as each `EmployeeZBonSummary.tipAmount`, and therefore what it may pay out inside itself (F58).
 *
 * One definition shared by the closing dialog's preview (`GET /api/z-bon/open-period-tips`), the
 * closing's up-front payout check and its binding in-transaction check, so the three cannot disagree.
 * The total is exactly [ZBonOwnerTipRule.employeeTips] over the employee's [DrawerCashDerivation.activeBills],
 * the expression `ZBonServiceImpl.createReport` stores; the bills are scoped exactly as
 * [DrawerCashDerivation.unbookedCashTakings] scopes them for the drawer count. Every function needs
 * an ambient transaction.
 *
 * **Cash / card split — display only.** The payout always pays the employee's whole tip in cash from
 * the drawer; the drawer advances the card part, which reaches the bank with the card settlement. The
 * split tells the operator how much of the cash handed out was never in the drawer. Per bill, the card
 * part is the tip up to the bill's non-cash tender (a tip keyed on the card terminal is inside the card
 * payment), and the rest is cash. Nothing books on the split.
 */
object OpenPeriodTips {
    /** One employee's open-period employee tips. [cash] + [card] = [total]; [total] > 0. */
    data class Position(
        val employee: Employee,
        val total: BigDecimal,
        val cash: BigDecimal,
        val card: BigDecimal,
    )

    /** The open period's positions, ordered by employee number. */
    fun current(): List<Position> {
        val since =
            ZBon
                .all()
                .orderBy(ZBonTable.id to SortOrder.DESC)
                .firstOrNull()
                ?.endTime
        val active = DrawerCashDerivation.activeBills(DrawerCashDerivation.unclosedBills(since))
        return of(active, DrawerCashDerivation.paymentsByBill(active))
    }

    /**
     * The positions over [activeBills] — the closing passes its own active bills, so its check runs on
     * the very set its employee summaries are built from.
     */
    fun of(
        activeBills: List<Bill>,
        paymentsByBill: Map<EntityID<Long>, List<Payment>>,
    ): List<Position> =
        activeBills
            .groupBy { it.employeeId }
            .map { (employee, bills) ->
                // The very expression the closing stores on the employee's summary.
                val total = ZBonOwnerTipRule.employeeTips(bills)
                val card =
                    bills
                        .filterNot { ZBonOwnerTipRule.isOwnerTip(it) }
                        .sumOf { cardPartOf(it, paymentsByBill[it.id].orEmpty()) }
                        .min(total.max(BigDecimal.ZERO))
                Position(employee = employee, total = total, cash = total - card, card = card)
            }.filter { it.total.signum() > 0 }
            .sortedBy { it.employee.employeeNumber }

    /** The part of [bill]'s tip tendered by card: the tip, capped at the bill's non-cash tender. */
    private fun cardPartOf(
        bill: Bill,
        payments: List<Payment>,
    ): BigDecimal {
        val nonCash = payments.filter { !it.paymentType.isCash }.sumOf { it.amount }
        if (nonCash.signum() <= 0 || bill.tips.signum() <= 0) return BigDecimal.ZERO
        return bill.tips.min(nonCash)
    }
}
