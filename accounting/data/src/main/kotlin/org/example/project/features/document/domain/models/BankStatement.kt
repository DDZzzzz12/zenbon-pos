/**
 * Bank Statement Entity - Exposed DAO
 *
 * Domain model representing a bank statement, the aggregate root for its transactions.
 *
 * Note: shares its simple name with `org.example.project.banking.model.BankStatement` in the
 * `:accounting-banking` module — different module, different package. Any file importing both needs an
 * alias (prefer `import org.example.project.banking.model.BankStatement as ParsedBankStatement`
 * at the call site).
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.domain.models

import org.example.project.features.bank.domain.models.BankAccount
import org.example.project.features.document.database.tables.BankStatementTable
import org.example.project.features.document.database.tables.BankStatementTransactionTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Exposed DAO entity for a bank statement.
 *
 * [sourceDocument] is nullable for the same reason [Invoice]'s is: a statement can be entered
 * manually or arrive without a stored file.
 */
class BankStatement(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<BankStatement>(BankStatementTable)

    /** Source document this statement was extracted from. Nullable — may be entered manually. */
    var sourceDocument by Document optionalReferencedOn BankStatementTable.sourceDocumentId

    /** Resolved own-account; null until `StatementAccountResolver` has run or a human assigned one. */
    var bankAccount by BankAccount optionalReferencedOn BankStatementTable.bankAccountId

    /** `camt053` | `mt940` | `pdf` | `manual`. */
    var format by BankStatementTable.format

    var accountIban by BankStatementTable.accountIban
    var accountBic by BankStatementTable.accountBic
    var bankName by BankStatementTable.bankName
    var accountNumber by BankStatementTable.accountNumber
    var statementNumber by BankStatementTable.statementNumber
    var legalSequenceNumber by BankStatementTable.legalSequenceNumber
    var statementDate by BankStatementTable.statementDate
    var periodStart by BankStatementTable.periodStart
    var periodEnd by BankStatementTable.periodEnd
    var openingBalance by BankStatementTable.openingBalance
    var closingBalance by BankStatementTable.closingBalance
    var currency by BankStatementTable.currency

    /**
     * The earliest already-stored statement for this account whose period overlaps this one, or
     * null when none overlaps. Overlapping statements are stored verbatim — nothing is merged or
     * removed; this only records what was noticed at ingest.
     */
    var overlappingStatement by BankStatement optionalReferencedOn BankStatementTable.overlappingStatementId

    var createdAt by BankStatementTable.createdAt
    var updatedAt by BankStatementTable.updatedAt

    /** Statement entries. */
    val transactions by BankStatementTransaction referrersOn BankStatementTransactionTable.statementId

    override fun toString(): String = "BankStatement(id=$id, statementNumber=$statementNumber, bank=$bankName)"
}
