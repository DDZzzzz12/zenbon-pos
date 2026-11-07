package org.example.project.features.kassenbuch.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.domain.models.CashBook
import org.example.project.infrastructure.errors.PosException
import java.math.BigDecimal
import java.util.UUID

/**
 * F59, option A: the Z-Bon dialog tops the drawer up from another cash book when the cash tip
 * payouts exceed what the drawer holds.
 *
 * Booked INSIDE the closing's transaction, after the aggregate and BEFORE the payouts, as an
 * ordinary Geldtransit through [CashBookTransferServiceImpl.createTransferLegsInTransaction] — the
 * same legs, the same Eigenbelege, the same Kassenminus guard on the source book and the same
 * signing (the Ladenkasse leg, CASH_TRANSFER_IN, is signed by the closing in phase 2 right after the
 * aggregate). A refusal anywhere in the closing rolls it back with everything else.
 *
 * How it interacts with the daily Geldtransit is [org.example.project.features.zBon.domain.ClosingCashTransfer]'s rule.
 */
internal class ClosingTopUp private constructor(
    val fromCashBookId: Long,
    val amount: BigDecimal,
) {
    /**
     * Books the transfer [fromCashBookId] → [fiscalBook]. 404 for an unknown book; 400 when the
     * source is the fiscal book itself, inactive, or another shop's; 409
     * `KASSENBUCH_INSUFFICIENT_CASH` when the source does not hold [amount]. Requires the closing's
     * transaction with `pg_advisory_xact_lock(1)` held.
     */
    suspend fun book(
        transferService: CashBookTransferServiceImpl,
        fiscalBook: CashBook,
        operator: Employee,
        belegDatum: String,
        zbonNummer: Long,
    ): CashBookTransferServiceImpl.TransferLegs {
        val source =
            CashBook.findById(fromCashBookId)
                ?: throw PosException(PosErrorCode.RESOURCE_NOT_FOUND, HttpStatusCode.NotFound, "Cash book with id=$fromCashBookId not found")
        if (source.isFiscal) throw invalid("The top-up must come from another cash book than the Ladenkasse")
        return transferService.createTransferLegsInTransaction(
            fromBook = source,
            toBook = fiscalBook,
            amount = amount,
            belegDatum = belegDatum,
            employee = operator,
            transferId = UUID.randomUUID().toString(),
            note = "Einlage für Trinkgeld-Auszahlung Z-Bon $zbonNummer",
            takings = KassenminusGuard.Takings.BOOKED_BY_CLOSING,
        )
    }

    companion object {
        /**
         * Fail fast on the request's shape — nothing is read. Null when no top-up is asked for. 400
         * unless the amount is a positive decimal with at most two places, and unless the closing
         * pays tips out in cash: a top-up exists only to cover those payouts.
         */
        fun parse(
            request: ZBonDto.ClosingTopUpRequestDto?,
            plan: ClosingTipPayoutPlan,
        ): ClosingTopUp? {
            if (request == null) return null
            val amount = EmployeeTipPayoutBooking.parseAmount(request.amount)
            if (plan.cashPayouts.isEmpty()) throw invalid("topUp is only allowed together with cash tip payouts")
            return ClosingTopUp(request.fromCashBookId, amount)
        }

        private fun invalid(detail: String) = PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, detail)
    }
}
