package org.example.project.features.kassenbuch.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.report.ZBonDto
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
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import java.util.UUID

/**
 * A requested storno of one employee-tip payout: the payout booked as Kassenbuch entry
 * [payoutHeaderId] against closing [zBonId].
 *
 * Owns the guards and the link row; the reversing entry itself is booked by
 * `KassenbuchServiceImpl.reverseEmployeeTipPayout` through the ordinary correction mechanism
 * (`CORRECTION_IN`, `correctsHeader` → the payout, role REVERSAL). [requirePayout] runs up front and
 * again under the Kassenbuch advisory lock via [hook], which is the check that holds against a
 * concurrent second storno; the unique index on `reverses_payout_id` is the structural backstop.
 */
internal class EmployeeTipPayoutReversal(
    val zBonId: Long,
    val payoutHeaderId: Long,
) {
    /** The payout row this storno reverses. */
    data class Payout(
        val id: Long,
        val employeeId: UUID,
        val amount: BigDecimal,
    )

    /**
     * The payout being reversed, after asserting it is one: 404 for an unknown closing; 409
     * [PosErrorCode.ZBON_TIP_PAYOUT_NOT_A_PAYOUT] when the entry is no payout of that closing (a
     * storno row included); 409 [PosErrorCode.ZBON_TIP_PAYOUT_ALREADY_REVERSED] when it was stornoed.
     */
    fun requirePayout(): Payout {
        ZBon.findById(zBonId)
            ?: throw PosException(PosErrorCode.ZBON_NOT_FOUND, HttpStatusCode.NotFound, "ZBon with id=$zBonId not found")
        val row =
            EmployeeTipPayoutTable
                .selectAll()
                .where {
                    (EmployeeTipPayoutTable.zBonId eq zBonId) and
                        (EmployeeTipPayoutTable.kassenbuchHeaderId eq payoutHeaderId) and
                        EmployeeTipPayoutTable.reversesPayoutId.isNull()
                }.firstOrNull()
                ?: throw PosException(
                    PosErrorCode.ZBON_TIP_PAYOUT_NOT_A_PAYOUT,
                    HttpStatusCode.Conflict,
                    "Kassenbuch entry $payoutHeaderId is not a tip payout of Z-Bon id=$zBonId",
                )
        val payout =
            Payout(
                id = row[EmployeeTipPayoutTable.id].value,
                employeeId = row[EmployeeTipPayoutTable.employeeId].value,
                amount = row[EmployeeTipPayoutTable.amount],
            )
        val alreadyReversed =
            EmployeeTipPayoutTable
                .selectAll()
                .where { EmployeeTipPayoutTable.reversesPayoutId eq payout.id }
                .any()
        if (alreadyReversed) {
            throw PosException(
                PosErrorCode.ZBON_TIP_PAYOUT_ALREADY_REVERSED,
                HttpStatusCode.Conflict,
                "Tip payout on Kassenbuch entry $payoutHeaderId has already been reversed",
            )
        }
        return payout
    }

    /** The employee's position after the storno — the freed amount is open again. */
    fun currentStatus(employeeId: UUID): ZBonDto.EmployeeTipPayoutStatusDto {
        val zBon = ZBon.findById(zBonId) ?: error("ZBon $zBonId vanished after its tip storno was booked")
        return EmployeeTipPayoutLedger.positionOf(zBon, employeeId)?.toStatusDto()
            ?: error("Employee $employeeId lost their tip position in Z-Bon $zBonId")
    }

    /** Re-check under the lock, then record the reversal row in the same transaction. */
    fun hook(createdBy: UUID): KassenbuchBookingHook =
        object : KassenbuchBookingHook {
            private var payout: Payout? = null

            override fun beforeCreate() {
                payout = requirePayout()
            }

            override fun afterCreate(headerId: Long) {
                val reversed = checkNotNull(payout) { "beforeCreate did not run" }
                EmployeeTipPayoutTable.insert {
                    it[EmployeeTipPayoutTable.zBonId] = this@EmployeeTipPayoutReversal.zBonId
                    it[EmployeeTipPayoutTable.employeeId] = reversed.employeeId
                    it[EmployeeTipPayoutTable.kassenbuchHeaderId] = headerId
                    it[EmployeeTipPayoutTable.amount] = reversed.amount
                    it[EmployeeTipPayoutTable.createdByEmployeeId] = createdBy
                    it[EmployeeTipPayoutTable.createdAt] = currentTimeStamp()
                    it[EmployeeTipPayoutTable.reversesPayoutId] = reversed.id
                }
            }
        }
}
