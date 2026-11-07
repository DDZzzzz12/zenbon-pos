package org.example.project.features.contract.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.contract.database.tables.ContractInstalmentTable
import org.example.project.features.contract.database.tables.ContractTable
import org.example.project.features.contract.domain.models.Contract
import org.example.project.features.contract.domain.models.ContractInstalment
import org.example.project.features.contract.domain.repository.ContractRepository
import org.example.project.features.contract.domain.repository.ContractValues
import org.example.project.features.document.database.tables.InvoiceTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.dao.entityCache
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.math.BigDecimal

/** Exposed implementation of [ContractRepository]. Requires an ambient transaction. */
class ContractRepositoryImpl : ContractRepository {
    override fun activeContracts(): List<Contract> = Contract.find { ContractTable.isActive eq true }.toList()

    override fun create(
        shopId: Long,
        values: ContractValues,
    ): Contract {
        val shop = requireNotNull(ShopInfo.findById(shopId)) { "No shop with id=$shopId" }
        return Contract.new {
            this.shop = shop
            write(values)
        }
    }

    /** [ContractTable.updatedAt] is stamped here so no caller can replace the fields without it. */
    override fun update(
        contract: Contract,
        values: ContractValues,
    ) {
        contract.write(values)
        contract.updatedAt = currentTimeStamp()
    }

    override fun findById(id: Long): Contract? = Contract.findById(id)

    override fun list(
        includeInactive: Boolean,
        search: String?,
    ): List<Contract> =
        Contract
            .find { listFilter(includeInactive, search) }
            .orderBy(ContractTable.name to SortOrder.ASC)
            .toList()

    /**
     * The active filter, narrowed by the search term when there is one.
     *
     * A blank term is **not** a filter — the trim-to-null is what stops an empty search box from
     * emptying the list.
     */
    private fun listFilter(
        includeInactive: Boolean,
        search: String?,
    ): Op<Boolean> {
        val base: Op<Boolean> = if (includeInactive) Op.TRUE else (ContractTable.isActive eq true)
        val term = search?.trim()?.takeIf { it.isNotEmpty() } ?: return base
        val pattern = "%${term.lowercase()}%"
        return base and
            ((ContractTable.name.lowerCase() like pattern) or (ContractTable.description.lowerCase() like pattern))
    }

    override fun findInstalment(id: Long): ContractInstalment? = ContractInstalment.findById(id)

    override fun instalmentsFor(contractId: Long): List<ContractInstalment> =
        ContractInstalment
            .find { ContractInstalmentTable.contractId eq contractId }
            .orderBy(
                ContractInstalmentTable.dueDate to SortOrder.ASC,
                ContractInstalmentTable.id to SortOrder.ASC,
            ).toList()

    /** The one place a [ContractValues] becomes columns, shared by create and update. */
    private fun Contract.write(values: ContractValues) {
        supplier = values.supplier
        name = values.name
        description = values.description
        expectedAmount = values.expectedAmount
        tolerancePct = values.tolerancePct
        toleranceAbs = values.toleranceAbs
        interval = values.interval.name
        dueDay = values.dueDay
        dueWeekday = values.dueWeekday
        startDate = values.startDate
        endDate = values.endDate
        noticePeriodDays = values.noticePeriodDays
        expectsInvoice = values.expectsInvoice
        sepaMandateId = values.sepaMandateId
        defaultAccountNo = values.defaultAccountNo
        defaultBuCode = values.defaultBuCode
        defaultCostCenter = values.defaultCostCenter
        document = values.document
        isActive = values.isActive
    }

    /**
     * One query for the whole page. The key comes from `readValues` rather than `contract.id`, which
     * would load the referenced [Contract] per row and put back the N+1 this method exists to remove.
     */
    override fun instalmentsForContracts(contractIds: List<Long>): Map<Long, List<ContractInstalment>> {
        if (contractIds.isEmpty()) return emptyMap()
        return ContractInstalment
            .find { ContractInstalmentTable.contractId inList contractIds }
            .orderBy(
                ContractInstalmentTable.dueDate to SortOrder.ASC,
                ContractInstalmentTable.id to SortOrder.ASC,
            ).toList()
            .groupBy { it.readValues[ContractInstalmentTable.contractId].value }
    }

    /**
     * One join rather than one lookup per instalment. The invoice columns are read straight out of
     * the result row, so no [org.example.project.features.document.domain.models.Invoice] entity is
     * materialised at all.
     *
     * A row whose invoice states neither total is left out of the map — there is no amount to judge,
     * and a zero would be an assertion the invoice never made.
     */
    override fun invoiceTotalsForInstalments(instalmentIds: List<Long>): Map<Long, BigDecimal> {
        if (instalmentIds.isEmpty()) return emptyMap()
        return ContractInstalmentTable
            .innerJoin(InvoiceTable)
            .select(ContractInstalmentTable.id, InvoiceTable.amountDue, InvoiceTable.taxInclusiveTotal)
            .where { ContractInstalmentTable.id inList instalmentIds }
            .mapNotNull { row ->
                val total = row[InvoiceTable.amountDue] ?: row[InvoiceTable.taxInclusiveTotal]
                total?.let { row[ContractInstalmentTable.id].value to it }
            }.toMap()
    }

    /**
     * The unique index on `(contract_id, due_date)` is the real guarantee; this check is what turns
     * a duplicate into a quiet no-op rather than an exception that would abort the whole run.
     *
     * The lookup is a DSL `SELECT`, which does not see pending DAO inserts — so the entity cache is
     * flushed first. Without that, the generator creating several instalments for one contract in a
     * single transaction would not see the ones it just wrote.
     */
    override fun createInstalmentIfAbsent(
        contractId: Long,
        dueDate: LocalDateTime,
        expectedAmount: BigDecimal?,
    ): ContractInstalment? {
        TransactionManager.current().entityCache.flush(listOf(ContractInstalmentTable))
        val alreadyThere =
            ContractInstalmentTable
                .select(ContractInstalmentTable.id)
                .where { (ContractInstalmentTable.contractId eq contractId) and (ContractInstalmentTable.dueDate eq dueDate) }
                .any()
        if (alreadyThere) return null

        val contract = requireNotNull(Contract.findById(contractId)) { "No contract with id=$contractId" }
        return ContractInstalment.new {
            this.contract = contract
            this.dueDate = dueDate
            this.expectedAmount = expectedAmount
        }
    }
}
