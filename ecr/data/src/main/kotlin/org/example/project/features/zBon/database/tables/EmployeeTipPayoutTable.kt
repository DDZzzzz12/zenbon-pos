package org.example.project.features.zBon.database.tables

import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * One row per employee-tip payout: the cash a closing's TrinkgeldAN was handed to an employee with —
 * inside that closing's own transaction (F58, from the "Z-Bon erstellen" dialog) or after it.
 *
 * [zBonId] is always the closing whose tips are paid. Which closing BOOKED the payout is the
 * Kassenbuch entry's own `zbon_id`, written by the sweep: the same closing for an in-closing payout,
 * the following one for a payout after it — so the distinction needs no column here.
 *
 * A [TipPayoutMethod.CASH] payout is an ordinary signed Kassenbuch withdrawal ([kassenbuchHeaderId],
 * line kind `EMPLOYEE_TIP`) in the fiscal book; this row only records which closing and which
 * employee it paid, so the still-payable remainder per (closing, employee) is computable. The
 * Kassenbuch entry is the fiscal record — this table never carries anything the entry does not.
 *
 * A [TipPayoutMethod.BANK_PAYROLL] settlement (F59) moved no cash: the tip was paid by bank transfer
 * or with the payroll. It has NO Kassenbuch entry ([kassenbuchHeaderId] null — a CHECK in V82 ties
 * the two together), so no DSFinV-K row and no DATEV booking; this row is the whole record, and it
 * only clears the open amount.
 *
 * Append-only: a row is never updated or deleted. A reversal is a second row pointing at the
 * payout through [reversesPayoutId]. Flyway: V82__schema_cash_and_fiscal.sql.
 */
object EmployeeTipPayoutTable : LongIdTable("employee_tip_payout", "id") {
    /** The closing whose employee tips this payout draws on. */
    val zBonId = reference("zbon_id", ZBonTable)

    /** The employee the tip was paid to. */
    val employeeId = reference("employee_id", EmployeeTable)

    /**
     * The Kassenbuch withdrawal that moved the cash — exactly one payout per entry. Null exactly when
     * [method] is [TipPayoutMethod.BANK_PAYROLL] (and on its reversal row).
     */
    val kassenbuchHeaderId = optReference("kassenbuch_header_id", KassenbuchHeaderTable).uniqueIndex()

    /** How the tip was settled; a reversal row carries the method of the payout it reverses. */
    val method = enumerationByName("method", 16, TipPayoutMethod::class).default(TipPayoutMethod.CASH)

    /** Positive amount paid out, equal to the magnitude of the withdrawal's line. */
    val amount = decimal("amount", precision = 19, scale = 4)

    /** The operator who booked the payout. */
    val createdByEmployeeId = reference("created_by_employee_id", EmployeeTable)
    val createdAt = datetime("created_at")

    /**
     * Set on a REVERSAL row: the payout row it reverses. A reversal is booked as its own signed
     * Kassenbuch correction entry ([kassenbuchHeaderId]) and frees [amount] again; the payout row
     * is never touched. Unique, so a payout is reversed at most once.
     */
    val reversesPayoutId = optReference("reverses_payout_id", EmployeeTipPayoutTable).uniqueIndex()

    init {
        index(false, zBonId, employeeId)
    }
}
