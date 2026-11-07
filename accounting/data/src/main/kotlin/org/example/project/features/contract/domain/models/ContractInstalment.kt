package org.example.project.features.contract.domain.models

import org.example.project.features.contract.database.tables.ContractInstalmentTable
import org.example.project.features.document.domain.models.Invoice
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

/**
 * Exposed DAO entity for one expected payment. See [ContractInstalmentTable] for why the
 * `(contract_id, due_date)` pair is unique and why [status] never holds `OVERDUE`.
 */
class ContractInstalment(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<ContractInstalment>(ContractInstalmentTable)

    var contract by Contract referencedOn ContractInstalmentTable.contractId

    var dueDate by ContractInstalmentTable.dueDate
    var expectedAmount by ContractInstalmentTable.expectedAmount

    /** Null forever on an invoice-less contract. */
    var invoice by Invoice optionalReferencedOn ContractInstalmentTable.invoiceId

    /** [ContractInstalmentStatus] name, never `OVERDUE`. */
    var status by ContractInstalmentTable.status

    var openAmount by ContractInstalmentTable.openAmount
    var createdAt by ContractInstalmentTable.createdAt

    override fun toString(): String = "ContractInstalment(id=$id, dueDate=$dueDate, status=$status)"
}
