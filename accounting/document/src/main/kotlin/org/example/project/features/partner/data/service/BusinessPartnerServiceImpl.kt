package org.example.project.features.partner.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.core.domain.dto.partner.BusinessPartnerDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.domain.service.TransactionAuditService
import org.example.project.features.debtor.database.tables.DebtorTable
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.partner.domain.mapper.toDto
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.partner.domain.repository.BusinessPartnerRepository
import org.example.project.features.partner.domain.service.BusinessPartnerService
import org.example.project.features.supplier.database.tables.SupplierBankAccountTable
import org.example.project.features.supplier.database.tables.SupplierTable
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.features.supplier.domain.models.SupplierBankAccount
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.slf4j.MDC
import java.util.UUID

/** See [BusinessPartnerService]. Every method maps inside its own [dbQuery]. */
class BusinessPartnerServiceImpl(
    private val partners: BusinessPartnerRepository,
    private val auditService: TransactionAuditService,
) : BusinessPartnerService {
    override suspend fun duplicates(limit: Int): BusinessPartnerDto.DuplicateListResponseDto =
        dbQuery {
            BusinessPartnerDto.DuplicateListResponseDto(
                groups =
                    partners.findDuplicateGroups(limit).map { group ->
                        BusinessPartnerDto.DuplicateGroupDto(
                            key = group.key,
                            partners = group.partners.map { it.toDto() },
                        )
                    },
            )
        }

    override suspend fun get(id: Long): BusinessPartnerDto.ResponseDto? = dbQuery { partners.findByIdFollowingMerges(id)?.toDto() }

    override suspend fun merge(
        survivorId: Long,
        losingId: Long,
        actingEmployeeId: String?,
    ) {
        // Captured HERE, before the dbQuery — same discipline and same reason as the TSE queue's
        // request-backed entry points. A merge is request-backed too: its only caller is
        // `PartnerRoutes`' POST /merge. MDC is thread-local and `recordMerge` opens its own
        // transaction two steps down, where reading it can find another thread's context, or none.
        val traceId = MDC.get(TRACE_ID_MDC_KEY)
        val summary = dbQuery { applyMerge(survivorId, losingId) }
        val actorNumber = actingEmployeeId?.let { dbQuery { employeeNumberOf(it) } }
        recordMerge(survivorId, losingId, actingEmployeeId, actorNumber, summary, traceId)
    }

    /** The whole write, inside one transaction. Returns what the audit entry needs. */
    private fun applyMerge(
        survivorId: Long,
        losingId: Long,
    ): MergeSummary {
        if (survivorId == losingId) {
            throw PosException(
                PosErrorCode.INVALID_REQUEST,
                HttpStatusCode.BadRequest,
                "A partner cannot be merged into itself",
            )
        }
        val survivor = requirePartner(survivorId)
        val loser = requirePartner(losingId)

        // Merging into or out of an already-merged party would build a chain that no screen shows
        // and that the pointer-following read has to walk. One hop stays legible.
        if (survivor.mergedInto != null || loser.mergedInto != null) {
            throw PosException(
                PosErrorCode.INVALID_REQUEST,
                HttpStatusCode.BadRequest,
                "An already-merged partner cannot be merged again",
            )
        }

        moveCreditorRole(survivor, loser)
        moveDebtorRole(survivor, loser)
        moveBankAccounts(survivor, loser)

        loser.mergedInto = survivor
        loser.updatedAt = currentTimeStamp()

        return MergeSummary(survivorName = survivor.name, losingName = loser.name)
    }

    /**
     * `supplier` carries `UNIQUE (partner_id)`, so two creditor roles cannot both survive.
     *
     * Which one does decides the creditor number the DATEV export writes and which invoices stay
     * attached — a judgement with fiscal reach, so it is refused rather than guessed.
     */
    private fun moveCreditorRole(
        survivor: BusinessPartner,
        loser: BusinessPartner,
    ) {
        val survivorCreditor = Supplier.find { SupplierTable.partnerId eq survivor.id.value }.firstOrNull()
        val losingCreditor = Supplier.find { SupplierTable.partnerId eq loser.id.value }.firstOrNull()
        if (survivorCreditor != null && losingCreditor != null) {
            throw PosException(
                PosErrorCode.PARTNER_MERGE_CREDITOR_CONFLICT,
                HttpStatusCode.Conflict,
                "Both partners carry a creditor role; merge the creditors first",
            )
        }
        losingCreditor?.partner = survivor
    }

    /**
     * `debtor.partner_id` is unique too, so two debtor roles cannot both survive either. Refused
     * like the creditor case, before any write — it used to fail on the unique index as a 500
     * (E2E round 4, R4-11).
     */
    private fun moveDebtorRole(
        survivor: BusinessPartner,
        loser: BusinessPartner,
    ) {
        val survivorDebtor = Debtor.find { DebtorTable.partnerId eq survivor.id.value }.firstOrNull()
        val losingDebtor = Debtor.find { DebtorTable.partnerId eq loser.id.value }.firstOrNull()
        if (survivorDebtor != null && losingDebtor != null) {
            throw PosException(
                PosErrorCode.PARTNER_MERGE_DEBTOR_CONFLICT,
                HttpStatusCode.Conflict,
                "Both partners carry a debtor role; merge the debtors first",
            )
        }
        losingDebtor?.partner = survivor
    }

    private fun moveBankAccounts(
        survivor: BusinessPartner,
        loser: BusinessPartner,
    ) {
        val known = survivor.bankAccounts.map { it.iban }.toMutableSet()
        SupplierBankAccount
            .find { SupplierBankAccountTable.partnerId eq loser.id.value }
            .toList()
            .forEach { account ->
                // The same IBAN entered on both sides is one account. Keeping the duplicate would
                // leave a row pointing at a party that is about to be hidden — and
                // `(partner_id, iban)` is unique, so moving it would fail outright.
                if (!known.add(account.iban)) account.delete() else account.partner = survivor
            }
    }

    /**
     * Master data with fiscal reach, so every merge is recorded.
     *
     * Outside the transaction above because [TransactionAuditService.logTransaction] opens its own.
     * With no principal the entry is attributed the way the queue worker attributes its unattended
     * TSE writes — employee `SYSTEM`, authorization `AUTOMATIC` — rather than to a made-up operator.
     * `transaction_audit_log.employee_id` is a plain varchar, not a foreign key, so no employee row
     * has to exist for either form.
     */
    private suspend fun recordMerge(
        survivorId: Long,
        losingId: Long,
        actingEmployeeId: String?,
        actorNumber: String?,
        summary: MergeSummary,
        traceId: String?,
    ) {
        val actor = actingEmployeeId ?: SYSTEM_EMPLOYEE_ID
        auditService.logTransaction(
            employeeId = actor,
            // The employee NUMBER, as every other audit writer stores it — this column used to get
            // the UUID, which the audit screen then printed (E2E round 4, R4-9). An id that names no
            // employee (or SYSTEM) is kept as it is rather than invented.
            employeeNumber = actorNumber ?: actor,
            authorizationType =
                if (actingEmployeeId == null) {
                    AUTOMATIC_AUTHORIZATION
                } else {
                    TransactionAuditDto.AuthorizationType.DIRECT.name
                },
            authorizingManagerId = actor,
            operation = TransactionAuditDto.TransactionOperation.MERGE_BUSINESS_PARTNER.name,
            entityType = "business_partner",
            entityId = survivorId.toString(),
            entityName = summary.survivorName,
            beforeState = mapOf("losingId" to losingId.toString(), "losingName" to summary.losingName),
            afterState = mapOf("survivorId" to survivorId.toString(), "survivorName" to summary.survivorName),
            reason = "Business partners declared the same company",
            traceId = traceId,
        )
    }

    /** The employee number for [employeeId], or null when it names no employee. Needs a transaction. */
    private fun employeeNumberOf(employeeId: String): String? =
        runCatching { UUID.fromString(employeeId) }
            .getOrNull()
            ?.let { Employee.findById(it)?.employeeNumber }

    private fun requirePartner(id: Long): BusinessPartner =
        BusinessPartner.findById(id)
            ?: throw PosException(
                PosErrorCode.RESOURCE_NOT_FOUND,
                HttpStatusCode.NotFound,
                "Business partner $id not found",
            )

    /** What the audit entry needs, read while the transaction is still open. */
    private data class MergeSummary(
        val survivorName: String,
        val losingName: String,
    )

    private companion object {
        /** How `TseSigningQueueServiceImpl` attributes a write with no principal behind it. */
        const val SYSTEM_EMPLOYEE_ID = "SYSTEM"

        /**
         * Not a [TransactionAuditDto.AuthorizationType] value: that enum has no unattended member,
         * and the queue worker already writes this literal for the same situation.
         */
        const val AUTOMATIC_AUTHORIZATION = "AUTOMATIC"

        /**
         * Mirrors `TRACE_ID_MDC_KEY` (platform-server, TraceIdMdcKey.kt), as `ExceptionConverter` and
         * `TseSigningQueueServiceImpl` do — this module must not depend on Ktor either.
         */
        const val TRACE_ID_MDC_KEY = "traceId"
    }
}
