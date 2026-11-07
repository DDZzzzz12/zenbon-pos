package org.example.project.features.kassenbuch.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.zBon.database.tables.EmployeeTipPayoutTable
import org.example.project.features.zBon.domain.EmployeeTipPayoutLedger
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.features.zBon.mapper.toStatusDto
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import java.math.BigDecimal
import java.util.UUID

/**
 * One requested employee-tip payout: [amount] of [employeeId]'s tips from closing [zBonId].
 *
 * Owns the payout's guards and its link row; the cash movement itself is booked by
 * `KassenbuchServiceImpl.payOutEmployeeTip` through the ordinary signed Kassenbuch path. The same
 * [requirePayable] runs twice — once up front, so a refused payout costs no Eigenbeleg, and again
 * under the Kassenbuch advisory lock via [hook], which is the check that actually holds against a
 * concurrent payout. Every function needs an ambient transaction except [parse].
 */
internal class EmployeeTipPayoutBooking private constructor(
    val zBonId: Long,
    val employeeId: UUID,
    val amount: BigDecimal,
) {
    /**
     * The employee's position BEFORE this payout, after asserting the payout fits in it.
     *
     * 404 when the closing or the employee does not exist; 409 [PosErrorCode.ZBON_TIP_PAYOUT_NOT_AVAILABLE]
     * when the closing is not payable at all or the employee holds no employee tip in it; 409
     * [PosErrorCode.ZBON_TIP_PAYOUT_EXCEEDS_REMAINING] when [amount] is more than is left.
     */
    fun requirePayable(): EmployeeTipPayoutLedger.Position {
        val zBon =
            ZBon.findById(zBonId)
                ?: throw PosException(PosErrorCode.ZBON_NOT_FOUND, HttpStatusCode.NotFound, "ZBon with id=$zBonId not found")
        Employee.findById(employeeId)
            ?: throw PosException(PosErrorCode.EMPLOYEE_NOT_FOUND, HttpStatusCode.NotFound, "Employee not found: $employeeId")
        if (!EmployeeTipPayoutLedger.isPayable(zBon)) {
            throw notAvailable(
                "Z-Bon ${zBon.zbonNummer} predates employee-tip bookings: its tips were booked as " +
                    "turnover, so they cannot be paid out through the till",
            )
        }
        val position =
            EmployeeTipPayoutLedger.positionOf(zBon, employeeId)
                ?: throw notAvailable("Employee $employeeId has no employee tip in Z-Bon ${zBon.zbonNummer}")
        if (amount > position.remaining) {
            throw PosException(
                PosErrorCode.ZBON_TIP_PAYOUT_EXCEEDS_REMAINING,
                HttpStatusCode.Conflict,
                "Tip payout ${amount.toPlainString()} exceeds the remaining ${position.remaining.toPlainString()} " +
                    "(tips ${position.tipTotal.toPlainString()}, already paid ${position.paidOut.toPlainString()}) " +
                    "for employee $employeeId in Z-Bon ${zBon.zbonNummer}",
            )
        }
        return position
    }

    /** The employee's position as it stands now — after a committed payout, its new remainder. */
    fun currentStatus(): ZBonDto.EmployeeTipPayoutStatusDto {
        val zBon = ZBon.findById(zBonId) ?: error("ZBon $zBonId vanished after its tip payout was booked")
        val position =
            EmployeeTipPayoutLedger.positionOf(zBon, employeeId)
                ?: error("Employee $employeeId lost their tip position in Z-Bon $zBonId")
        return position.toStatusDto()
    }

    /**
     * Records this payout as a [TipPayoutMethod.BANK_PAYROLL] settlement (F59, option C): the tip was
     * paid by bank transfer or with the payroll, so NO Kassenbuch entry exists — the row is the whole
     * record and only clears the open amount. Re-checks [requirePayable] first; the caller must hold
     * `pg_advisory_xact_lock(1)`, the lock every cash payout takes, so a cash payout and a settlement
     * for the same employee cannot both pass on the same remainder.
     *
     * @return the new row's id
     */
    fun recordBankPayrollSettlement(createdBy: UUID): Long {
        requirePayable()
        return EmployeeTipPayoutTable
            .insertAndGetId {
                it[EmployeeTipPayoutTable.zBonId] = this@EmployeeTipPayoutBooking.zBonId
                it[EmployeeTipPayoutTable.employeeId] = this@EmployeeTipPayoutBooking.employeeId
                it[EmployeeTipPayoutTable.kassenbuchHeaderId] = null
                it[EmployeeTipPayoutTable.method] = TipPayoutMethod.BANK_PAYROLL
                it[EmployeeTipPayoutTable.amount] = this@EmployeeTipPayoutBooking.amount
                it[EmployeeTipPayoutTable.createdByEmployeeId] = createdBy
                it[EmployeeTipPayoutTable.createdAt] = currentTimeStamp()
            }.value
    }

    /**
     * The in-transaction half: re-check under the lock, then record the link — so the guard and the
     * booking it guards commit together or not at all.
     */
    fun hook(createdBy: UUID): KassenbuchBookingHook =
        object : KassenbuchBookingHook {
            override fun beforeCreate() {
                requirePayable()
            }

            override fun afterCreate(headerId: Long) {
                EmployeeTipPayoutTable.insert {
                    it[EmployeeTipPayoutTable.zBonId] = this@EmployeeTipPayoutBooking.zBonId
                    it[EmployeeTipPayoutTable.employeeId] = this@EmployeeTipPayoutBooking.employeeId
                    it[EmployeeTipPayoutTable.kassenbuchHeaderId] = headerId
                    it[EmployeeTipPayoutTable.method] = TipPayoutMethod.CASH
                    it[EmployeeTipPayoutTable.amount] = this@EmployeeTipPayoutBooking.amount
                    it[EmployeeTipPayoutTable.createdByEmployeeId] = createdBy
                    it[EmployeeTipPayoutTable.createdAt] = currentTimeStamp()
                }
            }
        }

    private fun notAvailable(detail: String) = PosException(PosErrorCode.ZBON_TIP_PAYOUT_NOT_AVAILABLE, HttpStatusCode.Conflict, detail)

    companion object {
        /**
         * Fail fast on the request's shape — nothing is read. The amount must be a positive decimal
         * with at most two places (cash is handed out in cents); the employee id a UUID.
         */
        fun parse(
            zBonId: Long,
            request: ZBonDto.CreateTipPayoutRequestDto,
        ): EmployeeTipPayoutBooking = EmployeeTipPayoutBooking(zBonId, parseEmployeeId(request.employeeId), parseAmount(request.amount))

        /** The payout's employee id — 400 unless a UUID. Shared with the in-closing payout (F58). */
        fun parseEmployeeId(raw: String): UUID =
            runCatching { UUID.fromString(raw) }.getOrNull()
                ?: throw invalid("employeeId must be a UUID, got '$raw'")

        /**
         * The payout amount — 400 unless a positive decimal with at most two places (cash is handed
         * out in cents). Shared with the in-closing payout (F58), so both paths accept the same input.
         */
        fun parseAmount(raw: String): BigDecimal {
            val amount =
                raw.trim().toBigDecimalOrNull()
                    ?: throw invalid("amount must be a decimal number, got '$raw'")
            if (amount.signum() <= 0) throw invalid("amount must be greater than zero")
            if (amount.stripTrailingZeros().scale() > 2) throw invalid("amount must have at most two decimal places")
            return amount
        }

        private fun invalid(detail: String) = PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, detail)
    }
}
