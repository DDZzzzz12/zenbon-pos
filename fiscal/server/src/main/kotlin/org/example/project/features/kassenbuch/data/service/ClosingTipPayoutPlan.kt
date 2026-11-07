package org.example.project.features.kassenbuch.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.core.error.PosErrorCode
import org.example.project.features.zBon.domain.OpenPeriodTips
import org.example.project.infrastructure.errors.PosException
import java.math.BigDecimal
import java.util.UUID

/**
 * The employee-tip payouts a Z-Bon request asks to book INSIDE the closing (F58): parsed and
 * authorised up front, before anything is read, then checked inside the closing's transaction under
 * `pg_advisory_xact_lock(1)` against the very bills the closing is built from, before anything is
 * written. A refusal there rolls back the whole closing, the drawer count included.
 */
internal class ClosingTipPayoutPlan private constructor(
    val payouts: List<Payout>,
) {
    /**
     * One requested payout: [amount] (positive, ≤ 2 places) of [employeeId]'s open-period tips, paid
     * in cash from the drawer or — [TipPayoutMethod.BANK_PAYROLL], F59 — by bank transfer / payroll.
     */
    data class Payout(
        val employeeId: UUID,
        val amount: BigDecimal,
        val method: TipPayoutMethod = TipPayoutMethod.CASH,
    )

    val isEmpty: Boolean get() = payouts.isEmpty()

    /** The payouts handed out in cash — each one a signed Kassenbuch withdrawal. */
    val cashPayouts: List<Payout> get() = payouts.filter { it.method == TipPayoutMethod.CASH }

    /** The bank/payroll settlements — no Kassenbuch entry, no cash moves. */
    val bankPayrollSettlements: List<Payout> get() = payouts.filter { it.method == TipPayoutMethod.BANK_PAYROLL }

    /**
     * Σ of the CASH payouts — what leaves the drawer with them. A bank/payroll settlement takes
     * nothing out of the drawer, so it stays out of the Kassenminus guard, the drawer count and the
     * Geldtransit rule.
     */
    val cashTotal: BigDecimal get() = cashPayouts.fold(BigDecimal.ZERO) { acc, p -> acc + p.amount }

    /**
     * 403 when payouts are requested by an operator without `CASHBOOK_WRITE` — the capability of
     * `POST /api/z-bon/{id}/tip-payouts` and of every other withdrawal from the fiscal Ladenkasse.
     * `POST /api/z-bon` itself is `ZBON_CLOSE`; closing the day must not become a way to take cash
     * out of the drawer without being trusted with the cash book.
     */
    fun requireAuthorized(held: Set<Capability>) {
        if (isEmpty || Capability.CASHBOOK_WRITE in held) return
        throw PosException(
            PosErrorCode.AUTH_FORBIDDEN,
            HttpStatusCode.Forbidden,
            "Paying out employee tips in the closing requires ${Capability.CASHBOOK_WRITE.name}",
        )
    }

    /**
     * Every payout fits its employee's open-period employee tip: 409
     * [PosErrorCode.ZBON_TIP_PAYOUT_NOT_AVAILABLE] for an employee holding none, 409
     * [PosErrorCode.ZBON_TIP_PAYOUT_EXCEEDS_REMAINING] for more than they hold.
     */
    fun requireWithin(open: List<OpenPeriodTips.Position>) {
        val byEmployee = open.associateBy { it.employee.id.value }
        payouts.forEach { payout ->
            val position =
                byEmployee[payout.employeeId]
                    ?: throw PosException(
                        PosErrorCode.ZBON_TIP_PAYOUT_NOT_AVAILABLE,
                        HttpStatusCode.Conflict,
                        "Employee ${payout.employeeId} has no employee tip in the period being closed",
                    )
            if (payout.amount > position.total) {
                throw PosException(
                    PosErrorCode.ZBON_TIP_PAYOUT_EXCEEDS_REMAINING,
                    HttpStatusCode.Conflict,
                    "Tip payout ${payout.amount.toPlainString()} exceeds the ${position.total.toPlainString()} " +
                        "of employee tips employee ${payout.employeeId} holds in the period being closed",
                )
            }
        }
    }

    companion object {
        val NONE = ClosingTipPayoutPlan(emptyList())

        /**
         * Fail fast on the request's shape — nothing is read. Same amount and id rules as the payout
         * after the closing; one entry per employee (400 on a repeat, which would otherwise let the
         * per-employee limit be split across rows).
         */
        fun parse(requests: List<ZBonDto.CreateTipPayoutRequestDto>): ClosingTipPayoutPlan {
            if (requests.isEmpty()) return NONE
            val payouts =
                requests.map {
                    Payout(
                        employeeId = EmployeeTipPayoutBooking.parseEmployeeId(it.employeeId),
                        amount = EmployeeTipPayoutBooking.parseAmount(it.amount),
                        method = it.method,
                    )
                }
            val repeated = payouts.groupBy { it.employeeId }.filterValues { it.size > 1 }.keys
            if (repeated.isNotEmpty()) {
                throw PosException(
                    PosErrorCode.VALIDATION_FAILED,
                    HttpStatusCode.BadRequest,
                    "tipPayouts names employee ${repeated.first()} more than once",
                )
            }
            return ClosingTipPayoutPlan(payouts)
        }
    }
}
