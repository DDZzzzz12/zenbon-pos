package org.example.project.features.zBon.domain

import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.zBon.domain.models.EmployeeZBonSummary
import org.example.project.features.zBon.domain.models.ZBon
import java.math.BigDecimal

/**
 * THE Z-Bon rule for whose tips are owner tips (TrinkgeldAG — taxed turnover, inside the revenue)
 * and whose are employee tips (TrinkgeldAN — not revenue).
 *
 * **Creation** decides by the bill's stored [Bill.tipRecipient], never by the closer's role. That
 * is the field the bill's VAT rows, the signed process data, the DSFinV-K export
 * (`tipRecipient == OWNER`) and Statistik (`employeeTipSumExpr`) all key off, and a Zahlartwechsel
 * preserves it across a role change. Deciding by the CURRENT role made `adminTip` and
 * `totalPosRevenue` disagree with the VAT rows and DSFinV-K as soon as an employee was promoted or
 * demoted between the sale and the closing. So a closing stores:
 *  - `ZBon.adminTip` = Σ tips of the OWNER bills ([ownerTips]), and it alone carries the owner tip;
 *  - each `EmployeeZBonSummary.tipAmount` = Σ tips of that employee's EMPLOYEE bills
 *    ([employeeTips]) — never an owner tip, whatever the employee's role.
 *
 * **Rendering** a stored closing therefore needs no rule for current rows: no summary carries an
 * owner tip. Closings created BEFORE this change stored ALL of an ADMIN closer's tips on that
 * admin's summary (and the same amount in `adminTip`). For those legacy closings only, and only
 * for rendering, [summaryCarriesOwnerTip] falls back to the role: see [isLegacyClosing].
 */
object ZBonOwnerTipRule {
    fun isOwnerTip(bill: Bill): Boolean = bill.tipRecipient == TipRecipient.OWNER

    /** Σ tips of the OWNER bills among [bills] — the closing's `adminTip`. */
    fun ownerTips(bills: List<Bill>): BigDecimal = bills.filter { isOwnerTip(it) }.sumOf { it.tips }

    /** Σ tips of the EMPLOYEE bills among [bills] — what an employee summary stores. */
    fun employeeTips(bills: List<Bill>): BigDecimal = bills.filterNot { isOwnerTip(it) }.sumOf { it.tips }

    /**
     * True when [zBon] was created before summaries stopped carrying owner tips — LEGACY RENDERING
     * FALLBACK ONLY, never used when creating a closing.
     *
     * Schema-free discriminator: a legacy closing's `adminTip` was, by construction, exactly the sum
     * of its ADMIN summaries' tips, so a non-zero `adminTip` equal to that sum marks one. Its known
     * limits, both confined to rendering: an admin demoted since a legacy closing is no longer
     * recognised (its owner tip is then listed as an employee tip, the pre-fix display); and a
     * current closing whose re-promoted admin's employee tips equal the owner tips to the cent would
     * be mistaken for legacy. Fiscal figures (`adminTip`, `totalPosRevenue`) are stored and never
     * re-derived, so neither limit can move them.
     */
    fun isLegacyClosing(zBon: ZBon): Boolean {
        if (zBon.adminTip.signum() == 0) return false
        val adminRowTips = zBon.employeeSummaries.filter { it.employee.role == EmployeeRole.ADMIN }.sumOf { it.tipAmount }
        return adminRowTips.compareTo(zBon.adminTip) == 0
    }

    /** Whether [summary]'s stored tip is an owner tip (only ever on a legacy closing's ADMIN rows). */
    fun summaryCarriesOwnerTip(
        summary: EmployeeZBonSummary,
        legacyClosing: Boolean,
    ): Boolean = legacyClosing && summary.employee.role == EmployeeRole.ADMIN
}
