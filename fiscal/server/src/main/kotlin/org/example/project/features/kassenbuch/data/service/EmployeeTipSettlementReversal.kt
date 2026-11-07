package org.example.project.features.kassenbuch.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.core.error.PosErrorCode
import org.example.project.features.zBon.database.tables.EmployeeTipPayoutTable
import org.example.project.features.zBon.domain.EmployeeTipPayoutLedger
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.features.zBon.mapper.toStatusDto
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import java.util.UUID

/**
 * A requested storno of one [TipPayoutMethod.BANK_PAYROLL] settlement (F59, option C): row
 * [payoutId] of closing [zBonId].
 *
 * The same rules as a cash payout's storno ([EmployeeTipPayoutReversal]) — append-only, a second row
 * pointing at the settlement through `reverses_payout_id`, once only (the unique index is the
 * structural backstop), the amount open again — minus the Kassenbuch entry: the settlement moved no
 * cash, so its storno moves none either. Every function needs an ambient transaction; the caller
 * holds `pg_advisory_xact_lock(1)`.
 */
internal class EmployeeTipSettlementReversal(
    val zBonId: Long,
    val payoutId: Long,
) {
    /** The settlement row being reversed. */
    data class Settlement(
        val employeeId: UUID,
        val amount: BigDecimal,
    )

    /**
     * The settlement, after asserting it is one: 404 for an unknown closing; 409
     * [PosErrorCode.ZBON_TIP_PAYOUT_NOT_A_PAYOUT] when [payoutId] is not a bank/payroll settlement of
     * that closing (a cash payout, a reversal row, another closing's row); 409
     * [PosErrorCode.ZBON_TIP_PAYOUT_ALREADY_REVERSED] when it was stornoed already.
     */
    fun requireSettlement(): Settlement {
        ZBon.findById(zBonId)
            ?: throw PosException(PosErrorCode.ZBON_NOT_FOUND, HttpStatusCode.NotFound, "ZBon with id=$zBonId not found")
        val row =
            EmployeeTipPayoutTable
                .selectAll()
                .where {
                    (EmployeeTipPayoutTable.id eq payoutId) and
                        (EmployeeTipPayoutTable.zBonId eq zBonId) and
                        (EmployeeTipPayoutTable.method eq TipPayoutMethod.BANK_PAYROLL) and
                        EmployeeTipPayoutTable.reversesPayoutId.isNull()
                }.firstOrNull()
                ?: throw PosException(
                    PosErrorCode.ZBON_TIP_PAYOUT_NOT_A_PAYOUT,
                    HttpStatusCode.Conflict,
                    "Tip payout $payoutId is not a bank/payroll settlement of Z-Bon id=$zBonId",
                )
        val alreadyReversed =
            EmployeeTipPayoutTable
                .selectAll()
                .where { EmployeeTipPayoutTable.reversesPayoutId eq payoutId }
                .any()
        if (alreadyReversed) {
            throw PosException(
                PosErrorCode.ZBON_TIP_PAYOUT_ALREADY_REVERSED,
                HttpStatusCode.Conflict,
                "Tip settlement $payoutId has already been reversed",
            )
        }
        return Settlement(
            employeeId = row[EmployeeTipPayoutTable.employeeId].value,
            amount = row[EmployeeTipPayoutTable.amount],
        )
    }

    /** Re-checks, then writes the reversal row. @return its id and the settlement it reversed. */
    fun reverse(createdBy: UUID): Pair<Long, Settlement> {
        val settlement = requireSettlement()
        val id =
            EmployeeTipPayoutTable
                .insertAndGetId {
                    it[EmployeeTipPayoutTable.zBonId] = this@EmployeeTipSettlementReversal.zBonId
                    it[EmployeeTipPayoutTable.employeeId] = settlement.employeeId
                    it[EmployeeTipPayoutTable.kassenbuchHeaderId] = null
                    it[EmployeeTipPayoutTable.method] = TipPayoutMethod.BANK_PAYROLL
                    it[EmployeeTipPayoutTable.amount] = settlement.amount
                    it[EmployeeTipPayoutTable.createdByEmployeeId] = createdBy
                    it[EmployeeTipPayoutTable.createdAt] = currentTimeStamp()
                    it[EmployeeTipPayoutTable.reversesPayoutId] = this@EmployeeTipSettlementReversal.payoutId
                }.value
        return id to settlement
    }

    /** The employee's position after the storno — the freed amount is open again. */
    fun currentStatus(employeeId: UUID): ZBonDto.EmployeeTipPayoutStatusDto {
        val zBon = ZBon.findById(zBonId) ?: error("ZBon $zBonId vanished after its tip settlement storno")
        return EmployeeTipPayoutLedger.positionOf(zBon, employeeId)?.toStatusDto()
            ?: error("Employee $employeeId lost their tip position in Z-Bon $zBonId")
    }
}
