package org.example.project.features.zBon.domain

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.zBon.database.tables.EmployeeTipPayoutTable
import org.example.project.features.zBon.domain.models.ZBon
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import java.util.UUID

/**
 * THE rule for how much of a closing's employee tips (TrinkgeldAN) is still payable to whom.
 *
 * One definition shared by the Z-Bon detail (what the till offers to pay out) and the payout
 * booking (what it accepts), so the two cannot disagree. Every function needs an ambient
 * transaction.
 *
 * **Tip total** = the employee's `EmployeeZBonSummary.tipAmount` — Σ tips of the bills whose stored
 * tip recipient is the employee, cash AND card ([ZBonOwnerTipRule.employeeTips]). A legacy
 * closing's ADMIN summary that carries the OWNER tip ([ZBonOwnerTipRule.summaryCarriesOwnerTip])
 * is turnover, not a tip held for staff, and counts as zero.
 *
 * **Paid out** = Σ payout rows − Σ reversal rows (`reverses_payout_id` set) for (closing, employee):
 * a reversed payout frees its amount again, append-only — no row is ever edited or deleted. Both
 * methods count: a [TipPayoutMethod.BANK_PAYROLL] settlement (F59) clears the open amount exactly as a
 * cash payout does, it just books no Kassenbuch entry.
 *
 * **Eligibility cut-over.** Only a closing whose Kassenbuch aggregate carries an
 * [KassenbuchLineKind.EMPLOYEE_TIP] line is payable. That line is what credits the tip to the
 * Durchlaufende-Posten account the payout debits; a closing created before the kind existed booked
 * its tips as a STANDARD 0 % line on a revenue account, so a payout against it would debit an
 * account the tip never reached — and its tips may well have been paid out by hand long ago.
 * Older closings therefore offer nothing and refuse a payout, rather than guessing.
 */
object EmployeeTipPayoutLedger {
    /**
     * One booked payout as the till lists it, with whether it has since been reversed. A
     * [TipPayoutMethod.BANK_PAYROLL] settlement has no Kassenbuch entry: [kassenbuchHeaderId] and
     * [belegNr] are null.
     */
    data class Record(
        val payoutId: Long,
        val kassenbuchHeaderId: Long?,
        val belegNr: String?,
        val amount: BigDecimal,
        val createdAt: LocalDateTime,
        val reversed: Boolean,
        val method: TipPayoutMethod,
    )

    /** One employee's position in one closing. */
    data class Position(
        val employee: Employee,
        val tipTotal: BigDecimal,
        val paidOut: BigDecimal,
        val records: List<Record> = emptyList(),
    ) {
        val remaining: BigDecimal get() = tipTotal - paidOut
    }

    /** Whether [zBon]'s tips can be paid out through the ledger at all — see the class KDoc. */
    fun isPayable(zBon: ZBon): Boolean =
        zBon.kasseBuch.any { header ->
            header.isZbonAggregate && header.lineItems.any { it.kind == KassenbuchLineKind.EMPLOYEE_TIP }
        }

    /**
     * Every employee with a non-zero employee tip in [zBon], with what is still payable and the
     * payouts booked so far. Empty for a closing that is not [isPayable]. Ordered by employee number.
     */
    fun positions(zBon: ZBon): List<Position> {
        if (!isPayable(zBon)) return emptyList()
        val legacyClosing = ZBonOwnerTipRule.isLegacyClosing(zBon)
        val rows = rowsOf(zBon.id.value)
        val reversedIds = rows.mapNotNull { it.reversesPayoutId }.toSet()
        return zBon.employeeSummaries
            .filterNot { ZBonOwnerTipRule.summaryCarriesOwnerTip(it, legacyClosing) }
            .filter { it.tipAmount.signum() > 0 }
            .map { summary ->
                val employee = summary.employee
                val own = rows.filter { it.employeeId == employee.id.value }
                val paid = own.filter { it.reversesPayoutId == null }.sumOf { it.amount }
                val reversed = own.filter { it.reversesPayoutId != null }.sumOf { it.amount }
                Position(
                    employee = employee,
                    tipTotal = summary.tipAmount,
                    paidOut = paid - reversed,
                    records =
                        own
                            .filter { it.reversesPayoutId == null }
                            .map { it.toRecord(reversed = it.id in reversedIds) },
                )
            }.sortedBy { it.employee.employeeNumber }
    }

    /** [employeeId]'s position in [zBon], or null when they hold no payable employee tip there. */
    fun positionOf(
        zBon: ZBon,
        employeeId: UUID,
    ): Position? = positions(zBon).firstOrNull { it.employee.id.value == employeeId }

    private class Row(
        val id: Long,
        val employeeId: UUID,
        val kassenbuchHeaderId: Long?,
        val amount: BigDecimal,
        val createdAt: LocalDateTime,
        val reversesPayoutId: Long?,
        val method: TipPayoutMethod,
    ) {
        fun toRecord(reversed: Boolean) =
            Record(
                payoutId = id,
                kassenbuchHeaderId = kassenbuchHeaderId,
                belegNr = kassenbuchHeaderId?.let { KassenbuchHeader.findById(it)?.belegNr.orEmpty() },
                amount = amount,
                createdAt = createdAt,
                reversed = reversed,
                method = method,
            )
    }

    private fun rowsOf(zBonId: Long): List<Row> =
        EmployeeTipPayoutTable
            .selectAll()
            .where { EmployeeTipPayoutTable.zBonId eq zBonId }
            .orderBy(EmployeeTipPayoutTable.id)
            .map {
                Row(
                    id = it[EmployeeTipPayoutTable.id].value,
                    employeeId = it[EmployeeTipPayoutTable.employeeId].value,
                    kassenbuchHeaderId = it[EmployeeTipPayoutTable.kassenbuchHeaderId]?.value,
                    amount = it[EmployeeTipPayoutTable.amount],
                    createdAt = it[EmployeeTipPayoutTable.createdAt],
                    reversesPayoutId = it[EmployeeTipPayoutTable.reversesPayoutId]?.value,
                    method = it[EmployeeTipPayoutTable.method],
                )
            }
}
