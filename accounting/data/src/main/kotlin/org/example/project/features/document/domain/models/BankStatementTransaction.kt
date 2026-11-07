/**
 * Bank Statement Transaction Entity - Exposed DAO
 *
 * Domain model representing a single bank statement entry.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.document.domain.models

import org.example.project.features.document.database.tables.BankStatementTransactionTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Exposed DAO entity for a bank statement transaction.
 *
 * Replaces `document_bank_statement_metadata.transactions_json`. [amount] is always positive;
 * direction is in [creditDebit], matching ISO 20022's `CdtDbtInd`. [rawEntry] is an additive
 * escape hatch for unmodelled CAMT fields — no business logic reads it.
 */
class BankStatementTransaction(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<BankStatementTransaction>(BankStatementTransactionTable)

    /** Parent statement. */
    var statement by BankStatement referencedOn BankStatementTransactionTable.statementId

    var bookingDate by BankStatementTransactionTable.bookingDate
    var valueDate by BankStatementTransactionTable.valueDate
    var amount by BankStatementTransactionTable.amount
    var currency by BankStatementTransactionTable.currency

    /** `CREDIT` | `DEBIT`. */
    var creditDebit by BankStatementTransactionTable.creditDebit

    var status by BankStatementTransactionTable.status
    var bankTxDomain by BankStatementTransactionTable.bankTxDomain
    var bankTxFamily by BankStatementTransactionTable.bankTxFamily
    var bankTxSubFamily by BankStatementTransactionTable.bankTxSubFamily
    var accountServicerReference by BankStatementTransactionTable.accountServicerReference
    var endToEndId by BankStatementTransactionTable.endToEndId
    var mandateId by BankStatementTransactionTable.mandateId
    var creditorId by BankStatementTransactionTable.creditorId
    var counterpartyName by BankStatementTransactionTable.counterpartyName
    var counterpartyIban by BankStatementTransactionTable.counterpartyIban
    var counterpartyBic by BankStatementTransactionTable.counterpartyBic
    var remittanceUnstructured by BankStatementTransactionTable.remittanceUnstructured
    var remittanceStructuredRef by BankStatementTransactionTable.remittanceStructuredRef
    var reversalIndicator by BankStatementTransactionTable.reversalIndicator

    /**
     * When the reversal sweep last drew a conclusion about this entry — see
     * [BankStatementTransactionTable.reversalProcessedAt] for why a marker is needed at all.
     */
    var reversalProcessedAt by BankStatementTransactionTable.reversalProcessedAt

    var rawEntry by BankStatementTransactionTable.rawEntry

    /** Residual still unassigned. Derived but persisted — see [BankStatementTransactionTable.openAmount]. */
    var openAmount by BankStatementTransactionTable.openAmount

    /**
     * The already-stored transaction this one repeats, or null when it has not been seen before.
     * Duplicates are marked and never removed — a consumer must filter on this, or it will book the
     * same payment twice.
     */
    var duplicateOfTransaction by
        BankStatementTransaction optionalReferencedOn BankStatementTransactionTable.duplicateOfTransactionId

    override fun toString(): String = "BankStatementTransaction(id=$id, valueDate=$valueDate, amount=$amount, creditDebit=$creditDebit)"
}
