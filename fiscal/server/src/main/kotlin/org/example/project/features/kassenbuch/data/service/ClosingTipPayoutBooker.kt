package org.example.project.features.kassenbuch.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.domain.repository.KassenbuchRepository
import org.example.project.features.kassenbuch.domain.service.KassenbuchDocuments
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.zBon.database.tables.EmployeeTipPayoutTable
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.features.zBon.mapper.zBonDisplayName
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import java.math.BigDecimal
import java.util.UUID

/**
 * Books the employee-tip payouts of a [ClosingTipPayoutPlan] INSIDE the Z-Bon's own transaction (F58).
 *
 * **Atomicity.** `ZBonServiceImpl.createReport` calls [book] inside its phase-1 transaction, which
 * holds `pg_advisory_xact_lock(1)` — the lock every Kassenbuch write and every closing takes first.
 * The withdrawal entries, their Eigenbelege' document rows, the `employee_tip_payout` link rows and
 * the closing itself therefore commit together or not at all: a closing refused or failing after the
 * payouts were booked rolls them back with it, and a payout can never sit in the book without the
 * closing whose tips it paid. (An Eigenbeleg's PDF *file* written before a rollback stays on disk
 * unreferenced, exactly as the Z-Bon report PDF does in the same transaction.)
 *
 * **Signing.** Like the closing's aggregate and its Geldtransit leg, each entry is created PENDING
 * and its TSE signing data returned; the closing signs it in phase 2, after the commit, through
 * [KassenbuchTseSigner] — the same FAIL/QUEUE/Nachsignieren decision as every other Kassenbuch entry.
 * The payload is [KassenbuchSigningPayload], the definition the ordinary payout signs with.
 *
 * **Linking.** The link row's `zbon_id` is the closing being created — the closing whose tips the
 * payout pays, exactly as for a payout after the closing. Which closing BOOKED the entry is the
 * header's own `zbon_id`, set by the closing's sweep: equal for an in-closing payout, the following
 * closing for one after it. So the model needs no new column.
 */
internal class ClosingTipPayoutBooker(
    private val kassenbuchRepository: KassenbuchRepository,
    private val documents: KassenbuchDocuments,
) {
    /** One payout booked in the closing, PENDING until phase 2 signs it. */
    data class Booked(
        val employeeId: UUID,
        val recipientName: String,
        val amount: BigDecimal,
        val headerId: Long,
        val belegNr: String,
        val processType: String,
        val processData: String,
    )

    /** One bank/payroll settlement recorded in the closing (F59) — no Kassenbuch entry, nothing to sign. */
    data class Settled(
        val employeeId: UUID,
        val recipientName: String,
        val amount: BigDecimal,
        val payoutId: Long,
    )

    /** What [book] needs from the closing — all of it resolved inside the closing's transaction. */
    data class Closing(
        val report: ZBon,
        val shop: ShopInfo,
        val operator: Employee,
        val fiscalBookId: Long,
        val belegDatum: String,
    )

    /**
     * Books every CASH payout of [plan] as a withdrawal from the fiscal book and links it to the
     * closing. Requires the closing's ambient transaction, with its advisory lock held, [plan]
     * already checked against the closing's own bills, and the drawer already checked to cover
     * [ClosingTipPayoutPlan.cashTotal] (the Kassenminus guard).
     */
    suspend fun book(
        plan: ClosingTipPayoutPlan,
        closing: Closing,
    ): List<Booked> = plan.cashPayouts.map { bookOne(it, closing) }

    /**
     * Records every BANK_PAYROLL payout of [plan] (F59, option C): one `employee_tip_payout` row each,
     * linked to the closing, with no Kassenbuch entry — no cash moved, so nothing is signed, nothing
     * reaches DSFinV-K or DATEV, and the drawer is untouched. Same transaction as the closing.
     */
    fun recordSettlements(
        plan: ClosingTipPayoutPlan,
        closing: Closing,
    ): List<Settled> =
        plan.bankPayrollSettlements.map { payout ->
            val recipient =
                Employee.findById(payout.employeeId)
                    ?: throw PosException(PosErrorCode.EMPLOYEE_NOT_FOUND, HttpStatusCode.NotFound, "Employee not found: ${payout.employeeId}")
            val id =
                EmployeeTipPayoutTable.insertAndGetId {
                    it[EmployeeTipPayoutTable.zBonId] = closing.report.id.value
                    it[EmployeeTipPayoutTable.employeeId] = payout.employeeId
                    it[EmployeeTipPayoutTable.kassenbuchHeaderId] = null
                    it[EmployeeTipPayoutTable.method] = TipPayoutMethod.BANK_PAYROLL
                    it[EmployeeTipPayoutTable.amount] = payout.amount
                    it[EmployeeTipPayoutTable.createdByEmployeeId] = closing.operator.id.value
                    it[EmployeeTipPayoutTable.createdAt] = currentTimeStamp()
                }
            Settled(payout.employeeId, recipient.zBonDisplayName(), payout.amount, id.value)
        }

    private suspend fun bookOne(
        payout: ClosingTipPayoutPlan.Payout,
        closing: Closing,
    ): Booked {
        val recipient =
            Employee.findById(payout.employeeId)
                ?: throw PosException(PosErrorCode.EMPLOYEE_NOT_FOUND, HttpStatusCode.NotFound, "Employee not found: ${payout.employeeId}")
        val recipientName = recipient.zBonDisplayName()
        val belegNr = kassenbuchRepository.getNextBelegNr(closing.shop, closing.fiscalBookId)
        val description =
            "Auszahlung Trinkgeld (Arbeitnehmer) an $recipientName im Kassenabschluss Z-Bon Nr. ${closing.report.zbonNummer}"
        val eigenbeleg =
            EmployeeTipPayoutEntry.eigenbeleg(
                documents = documents,
                belegNr = belegNr,
                belegDatum = closing.belegDatum,
                amount = payout.amount,
                description = description,
                operatorId = closing.operator.id.value,
            )
        val request =
            EmployeeTipPayoutEntry
                .request(
                    amount = payout.amount,
                    recipientName = recipientName,
                    belegDatum = closing.belegDatum,
                    description = description,
                    documentId = eigenbeleg,
                ).copy(cashBookId = closing.fiscalBookId)
        val written =
            InClosingEntryWriter.write(
                kassenbuchRepository = kassenbuchRepository,
                shop = closing.shop,
                operator = closing.operator,
                request = request,
                belegNr = belegNr,
                systemLineKinds = EmployeeTipPayoutEntry.systemLineKinds,
            )
        val header = written.header

        EmployeeTipPayoutTable.insert {
            it[EmployeeTipPayoutTable.zBonId] = closing.report.id.value
            it[EmployeeTipPayoutTable.employeeId] = payout.employeeId
            it[EmployeeTipPayoutTable.kassenbuchHeaderId] = header.id.value
            it[EmployeeTipPayoutTable.method] = TipPayoutMethod.CASH
            it[EmployeeTipPayoutTable.amount] = payout.amount
            it[EmployeeTipPayoutTable.createdByEmployeeId] = closing.operator.id.value
            it[EmployeeTipPayoutTable.createdAt] = currentTimeStamp()
        }

        val payload = written.payload
        return Booked(
            employeeId = payout.employeeId,
            recipientName = recipientName,
            amount = payout.amount,
            headerId = header.id.value,
            belegNr = belegNr,
            processType = payload.processType,
            processData = payload.processData,
        )
    }
}
