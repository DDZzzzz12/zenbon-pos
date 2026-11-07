package org.example.project.features.document.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * One statement entry. Replaces `document_bank_statement_metadata.transactions_json`, whose DTO
 * carried only four fields and so silently discarded the counterparty data present in the source
 * statement.
 *
 * [amount] is always positive; direction is in [creditDebit], matching ISO 20022's `CdtDbtInd`.
 * [rawEntry] is an additive escape hatch for unmodelled CAMT fields — no business logic reads it.
 */
object BankStatementTransactionTable : LongIdTable("bank_statement_transaction", "id") {
    val statementId = reference("statement_id", BankStatementTable, onDelete = ReferenceOption.CASCADE)

    val bookingDate = datetime("booking_date")
    val valueDate = datetime("value_date")
    val amount = decimal("amount", 19, 4)
    val currency = varchar("currency", 3).default("EUR")

    /** `CREDIT` | `DEBIT`. */
    val creditDebit = varchar("credit_debit", 6)

    val status = varchar("status", 20).nullable()
    val bankTxDomain = varchar("bank_tx_domain", 20).nullable()
    val bankTxFamily = varchar("bank_tx_family", 20).nullable()
    val bankTxSubFamily = varchar("bank_tx_subfamily", 20).nullable()
    val accountServicerReference = varchar("account_servicer_reference", 100).nullable()
    val endToEndId = varchar("end_to_end_id", 100).nullable()
    val mandateId = varchar("mandate_id", 100).nullable()
    val creditorId = varchar("creditor_id", 100).nullable()
    val counterpartyName = varchar("counterparty_name", 255).nullable()
    val counterpartyIban = varchar("counterparty_iban", 34).nullable()
    val counterpartyBic = varchar("counterparty_bic", 11).nullable()
    val remittanceUnstructured = text("remittance_unstructured").nullable()
    val remittanceStructuredRef = varchar("remittance_structured_ref", 100).nullable()
    val reversalIndicator = bool("reversal_indicator").default(false)

    /**
     * When the reversal sweep last drew a conclusion about this entry — **not** when it voided
     * something.
     *
     * The marker exists for a case that is easy to dismiss. Voiding looks naturally idempotent:
     * after one sweep the reversed entry has no allocations left, so a second finds nothing. But an
     * operator may decide the *return* was itself the error — the bank re-presents it, or it was a
     * duplicate return — and re-allocate the original by hand. Without this column the next nightly
     * sweep would undo their work again, silently, forever, with no way to make it stick.
     *
     * A reversal that paired with nothing is stamped too: the sweep has looked and found nothing,
     * which is a conclusion rather than a failure, and re-deciding it every night would never
     * produce a different answer. Null on every non-reversal entry, which the sweep never reads.
     */
    val reversalProcessedAt = datetime("reversal_processed_at").nullable()

    val rawEntry = text("raw_entry").nullable()

    /**
     * What is still unassigned on this entry: `amount − Σ allocations`.
     *
     * **Derived but persisted.** A `SUM` subquery per row makes the assignment queue unindexable, so
     * `AllocationWriter` writes it in the same transaction as every allocation and a test recomputes
     * it from scratch to prove it has not drifted.
     *
     * Null means "no residual known yet" — distinct from zero, which means fully assigned.
     */
    val openAmount = decimal("open_amount", 19, 4).nullable()

    /**
     * The already-stored transaction this one repeats, or null when it has not been seen before.
     *
     * Marked, never removed: a false positive costs a misleading hint, a deletion costs a real
     * transaction. MT940 carries no per-entry identifier, so two genuine same-day payments of the
     * same amount are indistinguishable from a repeat — a consumer filters on this column, it does
     * not get to assume the row is absent.
     */
    val duplicateOfTransactionId =
        optReference("duplicate_of_transaction_id", BankStatementTransactionTable, onDelete = ReferenceOption.SET_NULL)

    init {
        index("idx_bank_statement_transaction_statement_id", false, statementId)
        index("idx_bank_statement_transaction_value_date", false, valueDate)
        index("idx_bank_statement_transaction_counterparty_iban", false, counterpartyIban)
        index("idx_bank_statement_transaction_open_amount", false, openAmount)

        index("idx_bank_statement_transaction_duplicate_of_transaction_id", false, duplicateOfTransactionId)
    }
}
