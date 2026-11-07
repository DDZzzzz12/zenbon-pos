package org.example.project.features.contract.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.contract.domain.models.Contract
import org.example.project.features.contract.domain.models.ContractInstalment
import java.math.BigDecimal

/**
 * Plain data access over `contract` and `contract_instalment`.
 *
 * **Every method requires an ambient transaction** — the repository never opens its own `dbQuery`,
 * matching every other repository here; the caller (the scheduled job) is where the transaction
 * starts.
 */
interface ContractRepository {
    /** Only contracts an operator has left switched on; an expired one is filtered by its schedule. */
    fun activeContracts(): List<Contract>

    fun create(
        shopId: Long,
        values: ContractValues,
    ): Contract

    /**
     * A full replace of the editable fields, [ContractValues.isActive] included — deactivation is an
     * edit, not a delete, because an instalment already allocated against is history and the
     * `ON DELETE CASCADE` would take it with the contract.
     */
    fun update(
        contract: Contract,
        values: ContractValues,
    )

    fun findById(id: Long): Contract?

    /**
     * Name-ordered. [includeInactive] adds the ones an operator has switched off; [search] matches
     * name or description case-insensitively and is **ignored when blank**.
     *
     * The description is searchable because a contract is often remembered by what it is for rather
     * than by the name it was filed under.
     */
    fun list(
        includeInactive: Boolean = false,
        search: String? = null,
    ): List<Contract>

    fun findInstalment(id: Long): ContractInstalment?

    /** Ordered `due_date ASC, id ASC` — an unstable order makes a contract's strip jump between loads. */
    fun instalmentsFor(contractId: Long): List<ContractInstalment>

    /**
     * The same rows as [instalmentsFor], for many contracts in one query and grouped by contract id.
     *
     * The list endpoint derives every contract's next due date and last payment from its
     * instalments; asking per contract would be an N+1 growing with the contract count.
     */
    fun instalmentsForContracts(contractIds: List<Long>): Map<Long, List<ContractInstalment>>

    /**
     * Each instalment's linked invoice total — `amount_due ?: tax_inclusive_total` — keyed by
     * instalment id, in one query.
     *
     * An absent key means no invoice is linked, which is what the alert derivation reads as a
     * missing document. Reading `instalment.invoice` per row instead would materialise the whole
     * referenced invoice for every instalment on the page.
     */
    fun invoiceTotalsForInstalments(instalmentIds: List<Long>): Map<Long, BigDecimal>

    /** Returns null when `(contractId, dueDate)` already exists — the idempotency the generator relies on. */
    fun createInstalmentIfAbsent(
        contractId: Long,
        dueDate: LocalDateTime,
        expectedAmount: BigDecimal?,
    ): ContractInstalment?
}
