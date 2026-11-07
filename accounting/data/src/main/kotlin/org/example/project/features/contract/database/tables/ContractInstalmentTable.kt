package org.example.project.features.contract.database.tables

import org.example.project.features.document.database.tables.InvoiceTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * One expected payment under a [ContractTable] row.
 *
 * Materialised ahead of its due date by the generator, idempotently: the unique index on
 * `(contract_id, due_date)` is what makes a second run of the job a no-op, rather than a flag
 * saying "already ran today" that a restart would lose.
 *
 * [invoiceId] stays null forever on an invoice-less contract; on an invoice-bearing one it is
 * filled when the period's invoice is identified.
 *
 * [status] never holds `OVERDUE` — that is derived at read time from [dueDate] and [openAmount].
 * A stored OVERDUE would be wrong from the moment the date passes until something runs.
 */
object ContractInstalmentTable : LongIdTable("contract_instalment", "id") {
    val contractId = reference("contract_id", ContractTable, onDelete = ReferenceOption.CASCADE)

    val dueDate = datetime("due_date")
    val expectedAmount = decimal("expected_amount", 19, 4).nullable()

    val invoiceId = optReference("invoice_id", InvoiceTable, onDelete = ReferenceOption.SET_NULL)

    /** [org.example.project.features.contract.domain.models.ContractInstalmentStatus] name, never `OVERDUE`. */
    val status = varchar("status", 20).default("OPEN")

    /**
     * What is still owed on this instalment.
     *
     * `AllocationWriter.recomputeInstalment` maintains it: on every allocate and every remove it
     * recomputes the residual from scratch out of the surviving allocations, never by applying a
     * delta, so a lost or replayed write cannot drift the column away from the rows.
     *
     * A NULL means **no allocation has been computed against this instalment**, not that it is
     * settled. That is why the open-instalment query coalesces a NULL here to `expected_amount`
     * rather than reading it as zero — an untouched instalment is fully open.
     */
    val openAmount = decimal("open_amount", 19, 4).nullable()

    /** MUST stay `clientDefault { }` — see [ContractTable.createdAt]. */
    val createdAt = datetime("created_at").clientDefault { currentTimeStamp() }

    init {
        uniqueIndex(contractId, dueDate)
        index("idx_contract_instalment_due_date", false, dueDate)
        index("idx_contract_instalment_invoice_id", false, invoiceId)
    }
}
